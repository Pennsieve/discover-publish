/*
 * Copyright 2021 University of Pennsylvania
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.pennsieve.publish

import com.github.tototoshi.csv._
import com.typesafe.scalalogging.LazyLogging
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client

import java.io.File
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{ Duration, FiniteDuration, SECONDS }
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{ Failure, Success, Try }

case class CsvCopySettings(
  csvFilePath: String = "",
  region: Region = CsvCopySettings.DEFAULT_REGION,
  maxPartSize: Long = CsvCopySettings.MAX_PART_SIZE,
  maxWaitTime: Duration = CsvCopySettings.MAX_WAIT_TIME,
  parallelism: Int = CsvCopySettings.DEFAULT_PARALLELISM
) {
  def withSetting(name: String, value: String): CsvCopySettings =
    name match {
      case "--csv" =>
        this.copy(csvFilePath = value)
      case "--region" =>
        this.copy(region = Region.of(value))
      case "--maxPartSize" =>
        this.copy(maxPartSize = value.toLong)
      case "--maxWaitTime" =>
        this.copy(
          maxWaitTime = FiniteDuration(Duration(value).toSeconds, SECONDS)
        )
      case "--parallelism" =>
        this.copy(parallelism = value.toInt)
      case _ => // ignore any unrecognized names
        this
    }
}

object CsvCopySettings {
  val DEFAULT_REGION: Region = Region.US_EAST_1
  val MAX_PART_SIZE: Long = 50 * 1024 * 1024 // 50 MB
  val MAX_WAIT_TIME: FiniteDuration = Duration(60, TimeUnit.MINUTES)
  val DEFAULT_PARALLELISM: Int = 1

  // Environment variable names
  object EnvVars {
    val CSV_FILE_PATH = "CSV_FILE_PATH"
    val AWS_REGION = "AWS_REGION"
    val MAX_PART_SIZE = "MAX_PART_SIZE"
    val MAX_WAIT_TIME = "MAX_WAIT_TIME"
    val PARALLELISM = "PARALLELISM"
  }

  def apply(): CsvCopySettings = new CsvCopySettings()

  /**
    * Load settings from environment variables
    */
  def fromEnvironment(): CsvCopySettings = {
    val csvPath = sys.env.getOrElse(EnvVars.CSV_FILE_PATH, "")

    val region = sys.env.get(EnvVars.AWS_REGION) match {
      case Some(r) if r.nonEmpty => Region.of(r)
      case _ => DEFAULT_REGION
    }

    val maxPartSize = sys.env.get(EnvVars.MAX_PART_SIZE) match {
      case Some(size) if size.nonEmpty =>
        Try(size.toLong).getOrElse(MAX_PART_SIZE)
      case _ => MAX_PART_SIZE
    }

    val maxWaitTime = sys.env.get(EnvVars.MAX_WAIT_TIME) match {
      case Some(time) if time.nonEmpty =>
        Try(FiniteDuration(Duration(time).toSeconds, SECONDS))
          .getOrElse(MAX_WAIT_TIME)
      case _ => MAX_WAIT_TIME
    }

    val parallelism = sys.env.get(EnvVars.PARALLELISM) match {
      case Some(p) if p.nonEmpty =>
        Try(p.toInt).getOrElse(DEFAULT_PARALLELISM)
      case _ => DEFAULT_PARALLELISM
    }

    CsvCopySettings(
      csvFilePath = csvPath,
      region = region,
      maxPartSize = maxPartSize,
      maxWaitTime = maxWaitTime,
      parallelism = parallelism
    )
  }

  /**
    * Parse command line arguments and override settings
    * Command line arguments take priority over existing settings
    */
  def fromArgs(args: List[String], settings: CsvCopySettings): CsvCopySettings = {
    args match {
      case h :: t if t.nonEmpty =>
        fromArgs(t.drop(1), settings.withSetting(h, t.head))
      case _ =>
        settings
    }
  }
}

object CsvS3CopyMain extends LazyLogging {

  /**
    * Column names expected in the CSV file
    */
  object ColumnNames {
    val SOURCE_BUCKET = "source_bucket"
    val SOURCE_KEY = "source_key"
    val SOURCE_VERSION_ID = "source_version_id"
    val DESTINATION_BUCKET = "destination_bucket"
    val DESTINATION_KEY = "destination_key"
  }

  def s3Client(region: Region): S3Client = {
    val sharedHttpClient = UrlConnectionHttpClient.builder().build()
    S3Client.builder
      .region(region)
      .httpClient(sharedHttpClient)
      .build
  }

  /**
    * Parse a CSV row into a CopyRequest
    * Expected CSV columns: source_bucket, source_key, source_version_id (optional), destination_bucket, destination_key
    */
  def parseCsvRow(
    row: Map[String, String],
    rowNumber: Int
  ): Either[String, CopyRequest] = {
    for {
      sourceBucket <- row
        .get(ColumnNames.SOURCE_BUCKET)
        .filter(_.nonEmpty)
        .toRight(
          s"Row $rowNumber: Missing or empty '${ColumnNames.SOURCE_BUCKET}'"
        )

      sourceKey <- row
        .get(ColumnNames.SOURCE_KEY)
        .filter(_.nonEmpty)
        .toRight(s"Row $rowNumber: Missing or empty '${ColumnNames.SOURCE_KEY}'")

      destinationBucket <- row
        .get(ColumnNames.DESTINATION_BUCKET)
        .filter(_.nonEmpty)
        .toRight(
          s"Row $rowNumber: Missing or empty '${ColumnNames.DESTINATION_BUCKET}'"
        )

      destinationKey <- row
        .get(ColumnNames.DESTINATION_KEY)
        .filter(_.nonEmpty)
        .toRight(
          s"Row $rowNumber: Missing or empty '${ColumnNames.DESTINATION_KEY}'"
        )

    } yield {
      val sourceVersionId = row
        .get(ColumnNames.SOURCE_VERSION_ID)
        .filter(_.nonEmpty)

      CopyRequest(
        sourceBucket = sourceBucket,
        sourceKey = sourceKey,
        sourceS3VersionId = sourceVersionId,
        destinationBucket = destinationBucket,
        destinationKey = destinationKey
      )
    }
  }

  /**
    * Read and parse CSV file into a list of CopyRequests
    */
  def readCsvFile(csvFilePath: String): Either[String, List[CopyRequest]] = {
    Try {
      val file = new File(csvFilePath)
      if (!file.exists()) {
        throw new IllegalArgumentException(s"CSV file not found: $csvFilePath")
      }

      val reader = CSVReader.open(file)
      try {
        val rows = reader.allWithHeaders()
        val results = rows.zipWithIndex.map {
          case (row, index) =>
            parseCsvRow(row, index + 2) // +2 because row 1 is header, and index is 0-based
        }

        // Check for any parsing errors
        val errors = results.collect { case Left(error) => error }
        if (errors.nonEmpty) {
          Left(errors.mkString("\n"))
        } else {
          val requests = results.collect { case Right(request) => request }
          Right(requests)
        }
      } finally {
        reader.close()
      }
    } match {
      case Success(result) => result
      case Failure(exception) =>
        Left(s"Failed to read CSV file: ${exception.getMessage}")
    }
  }

  /**
    * Process a batch of copy requests sequentially
    */
  def processCopyRequests(
    requests: List[CopyRequest],
    uploader: MultipartUploader,
    settings: CsvCopySettings
  )(implicit
    ec: ExecutionContext
  ): Future[List[Either[Throwable, CompletedRequest]]] = {
    logger.info(s"Processing ${requests.length} copy requests")

    // Process requests with controlled parallelism
    val batches =
      requests.grouped(settings.parallelism).toList

    batches.foldLeft(Future.successful(List.empty[Either[Throwable, CompletedRequest]])) {
      (accFuture, batch) =>
        accFuture.flatMap { acc =>
          // Process this batch in parallel
          val batchFutures = batch.map { request =>
            logger.info(
              s"Copying ${request.sourceBucket}/${request.sourceKey} -> ${request.destinationBucket}/${request.destinationKey}"
            )

            uploader
              .copy(request)
              .map { result =>
                logger.info(
                  s"Successfully copied to ${result.bucket}/${result.key} (${result.operation})"
                )
                Right(result): Either[Throwable, CompletedRequest]
              }
              .recover {
                case ex: Throwable =>
                  logger.error(
                    s"Failed to copy ${request.sourceBucket}/${request.sourceKey} -> ${request.destinationBucket}/${request.destinationKey}",
                    ex
                  )
                  Left(ex): Either[Throwable, CompletedRequest]
              }
          }

          Future.sequence(batchFutures).map(acc ++ _)
        }
    }
  }

  def printUsage(): Unit = {
    println("""
      |Usage: CsvS3CopyMain --csv <path-to-csv> [options]
      |
      |Configuration Priority (highest to lowest):
      |  1. Command line arguments
      |  2. Environment variables
      |  3. Default values
      |
      |Required:
      |  --csv <path>          Path to CSV file with copy instructions
      |                        (or set CSV_FILE_PATH environment variable)
      |
      |Optional:
      |  --region <region>     AWS region (default: us-east-1)
      |                        (or set AWS_REGION environment variable)
      |  --maxPartSize <size>  Maximum part size in bytes (default: 50MB)
      |                        (or set MAX_PART_SIZE environment variable)
      |  --maxWaitTime <time>  Maximum wait time (e.g., "60m", "1h") (default: 60m)
      |                        (or set MAX_WAIT_TIME environment variable)
      |  --parallelism <n>     Number of parallel copy operations (default: 1)
      |                        (or set PARALLELISM environment variable)
      |
      |Environment Variables:
      |  CSV_FILE_PATH         Path to CSV file
      |  AWS_REGION            AWS region (e.g., us-east-1, us-west-2)
      |  MAX_PART_SIZE         Maximum part size in bytes
      |  MAX_WAIT_TIME         Maximum wait time (e.g., "60m", "2h")
      |  PARALLELISM           Number of parallel operations
      |
      |CSV Format:
      |  The CSV file must have a header row with the following columns:
      |    - source_bucket      : Source S3 bucket name
      |    - source_key         : Source S3 object key
      |    - source_version_id  : (Optional) Source S3 version ID
      |    - destination_bucket : Destination S3 bucket name
      |    - destination_key    : Destination S3 object key
      |
      |Example CSV:
      |  source_bucket,source_key,source_version_id,destination_bucket,destination_key
      |  my-source-bucket,path/to/file1.txt,,my-dest-bucket,new/path/file1.txt
      |  my-source-bucket,path/to/file2.txt,abc123,my-dest-bucket,new/path/file2.txt
      |
      |Example with environment variables:
      |  export CSV_FILE_PATH=/path/to/file.csv
      |  export AWS_REGION=us-west-2
      |  export PARALLELISM=3
      |  sbt "runMain com.pennsieve.publish.CsvS3CopyMain"
      |""".stripMargin)
  }

  def main(args: Array[String]): Unit = {
    logger.info("CsvS3CopyMain starting")

    if (args.contains("--help") || args.contains("-h")) {
      printUsage()
      sys.exit(0)
    }

    // Load settings: environment variables first, then override with command line args
    val envSettings = CsvCopySettings.fromEnvironment()
    val settings = CsvCopySettings.fromArgs(args.toList, envSettings)

    if (settings.csvFilePath.isEmpty) {
      logger.error("CSV file path is required (use --csv or CSV_FILE_PATH environment variable)")
      printUsage()
      sys.exit(1)
    }

    logger.info(s"Settings: $settings")

    // Read and parse CSV file
    val copyRequestsEither = readCsvFile(settings.csvFilePath)

    copyRequestsEither match {
      case Left(error) =>
        logger.error(s"Failed to parse CSV file: $error")
        sys.exit(1)

      case Right(copyRequests) =>
        logger.info(s"Successfully parsed ${copyRequests.length} copy requests")

        if (copyRequests.isEmpty) {
          logger.warn("No copy requests found in CSV file")
          sys.exit(0)
        }

        // Initialize S3 client and uploader
        val client = s3Client(settings.region)
        val uploader = MultipartUploader(client, settings.maxPartSize)

        try {
          // Process all copy requests
          val resultsFuture = processCopyRequests(copyRequests, uploader, settings)
          val results = Await.result(resultsFuture, settings.maxWaitTime)

          // Summary
          val successful = results.count(_.isRight)
          val failed = results.count(_.isLeft)

          logger.info(s"Copy operations completed: $successful successful, $failed failed")

          if (failed > 0) {
            logger.error("Some copy operations failed")
            sys.exit(1)
          } else {
            logger.info("All copy operations completed successfully")
            sys.exit(0)
          }

        } catch {
          case ex: Throwable =>
            logger.error("Unexpected error during copy operations", ex)
            sys.exit(1)
        } finally {
          client.close()
        }
    }
  }
}
