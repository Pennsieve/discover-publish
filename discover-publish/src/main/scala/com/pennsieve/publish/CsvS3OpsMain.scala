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
import software.amazon.awssdk.core.sync.ResponseTransformer
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  DeleteObjectRequest,
  DeleteObjectsRequest,
  GetObjectAttributesRequest,
  GetObjectRequest,
  HeadObjectRequest,
  ListObjectVersionsRequest,
  ObjectAttributes,
  RequestPayer
}

import java.io.File
import java.nio.file.{ Files, Path, StandardCopyOption }
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.{ Duration, FiniteDuration, SECONDS }
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }
import java.util.concurrent.ForkJoinPool

case class CsvOpsSettings(
  csvFilePath: String = "",
  region: Region = CsvOpsSettings.DEFAULT_REGION,
  maxPartSize: Long = CsvOpsSettings.MAX_PART_SIZE,
  maxWaitTime: Duration = CsvOpsSettings.MAX_WAIT_TIME,
  parallelism: Int = CsvOpsSettings.DEFAULT_PARALLELISM
) {
  def withSetting(name: String, value: String): CsvOpsSettings =
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

object CsvOpsSettings {
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

  def apply(): CsvOpsSettings = new CsvOpsSettings()

  /**
    * Load settings from environment variables
    */
  def fromEnvironment(): CsvOpsSettings = {
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

    CsvOpsSettings(
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
  def fromArgs(
    args: List[String],
    settings: CsvOpsSettings
  ): CsvOpsSettings = {
    args match {
      case h :: t if t.nonEmpty =>
        fromArgs(t.drop(1), settings.withSetting(h, t.head))
      case _ =>
        settings
    }
  }
}

object CsvS3OpsMain extends LazyLogging {

  /**
    * Column names expected in the CSV file
    */
  object ColumnNames {
    val OPERATION = "operation"
    val SOURCE_BUCKET = "source_bucket"
    val SOURCE_KEY = "source_key"
    val SOURCE_VERSION_ID = "source_version_id"
    val DESTINATION_BUCKET = "destination_bucket"
    val DESTINATION_KEY = "destination_key"
  }

  /**
    * Supported S3 operations
    */
  sealed trait S3Operation
  object S3Operation {
    case object COPY extends S3Operation
    case object DELETE extends S3Operation
    case object LIST extends S3Operation
    case object KEEP extends S3Operation

    def fromString(s: String): Either[String, S3Operation] = {
      s.toUpperCase.trim match {
        case "COPY" => Right(COPY)
        case "DELETE" => Right(DELETE)
        case "LIST" => Right(LIST)
        case "KEEP" => Right(KEEP)
        case other =>
          Left(
            s"Invalid operation: '$other'. Must be one of: COPY, DELETE, LIST, KEEP"
          )
      }
    }
  }

  /**
    * Request for any S3 operation
    */
  case class S3OperationRequest(
    operation: S3Operation,
    sourceBucket: String,
    sourceKey: String,
    sourceS3VersionId: Option[String] = None,
    destinationBucket: Option[String] = None,
    destinationKey: Option[String] = None
  )

  def s3Client(region: Region): S3Client = {
    val sharedHttpClient = UrlConnectionHttpClient.builder().build()
    S3Client.builder
      .region(region)
      .httpClient(sharedHttpClient)
      .build
  }

  /**
    * Parse an S3 URI into bucket and key
    * Supports formats: s3://bucket/key or s3://bucket/path/to/key
    */
  def parseS3Uri(uri: String): Option[(String, String)] = {
    val s3UriPattern = "^s3://([^/]+)/(.+)$".r
    uri match {
      case s3UriPattern(bucket, key) => Some((bucket, key))
      case _ => None
    }
  }

  /**
    * Check if the path is an S3 URI
    */
  def isS3Uri(path: String): Boolean = {
    path.startsWith("s3://")
  }

  /**
    * Download a file from S3 to a temporary location
    */
  def downloadFromS3(
    bucket: String,
    key: String,
    client: S3Client
  ): Either[String, File] = {
    Try {
      logger.info(s"Downloading CSV file from S3: s3://$bucket/$key")

      // Create temporary file
      val tempFile = Files.createTempFile("csv-s3-copy-", ".csv").toFile
      tempFile.deleteOnExit()

      val getObjectRequest = GetObjectRequest
        .builder()
        .bucket(bucket)
        .key(key)
        .requestPayer(RequestPayer.REQUESTER)
        .build()

      client.getObject(getObjectRequest, tempFile.toPath)

      logger.info(
        s"Downloaded CSV file to temporary location: ${tempFile.getAbsolutePath}"
      )
      tempFile
    } match {
      case Success(file) => Right(file)
      case Failure(exception) =>
        Left(s"Failed to download CSV file from S3: ${exception.getMessage}")
    }
  }

  /**
    * Parse a CSV row into an S3OperationRequest
    * Expected CSV columns: operation, source_bucket, source_key, source_version_id (optional), destination_bucket (for COPY), destination_key (for COPY)
    */
  def parseCsvRow(
    row: Map[String, String],
    rowNumber: Int
  ): Either[String, S3OperationRequest] = {
    for {
      // Parse operation (required)
      operationStr <- row
        .get(ColumnNames.OPERATION)
        .filter(_.nonEmpty)
        .toRight(s"Row $rowNumber: Missing or empty '${ColumnNames.OPERATION}'")

      operation <- S3Operation
        .fromString(operationStr)
        .left
        .map(err => s"Row $rowNumber: $err")

      // Parse source bucket and key (always required)
      sourceBucket <- row
        .get(ColumnNames.SOURCE_BUCKET)
        .filter(_.nonEmpty)
        .toRight(
          s"Row $rowNumber: Missing or empty '${ColumnNames.SOURCE_BUCKET}'"
        )

      sourceKey <- row
        .get(ColumnNames.SOURCE_KEY)
        .filter(_.nonEmpty)
        .toRight(
          s"Row $rowNumber: Missing or empty '${ColumnNames.SOURCE_KEY}'"
        )

      // For COPY operation, destination bucket and key are required
      destinationBucket <- operation match {
        case S3Operation.COPY =>
          row
            .get(ColumnNames.DESTINATION_BUCKET)
            .filter(_.nonEmpty)
            .toRight(
              s"Row $rowNumber: Missing or empty '${ColumnNames.DESTINATION_BUCKET}' (required for COPY operation)"
            )
            .map(Some(_))
        case _ => Right(None)
      }

      destinationKey <- operation match {
        case S3Operation.COPY =>
          row
            .get(ColumnNames.DESTINATION_KEY)
            .filter(_.nonEmpty)
            .toRight(
              s"Row $rowNumber: Missing or empty '${ColumnNames.DESTINATION_KEY}' (required for COPY operation)"
            )
            .map(Some(_))
        case _ => Right(None)
      }

    } yield {
      val sourceVersionId = row
        .get(ColumnNames.SOURCE_VERSION_ID)
        .filter(_.nonEmpty)

      S3OperationRequest(
        operation = operation,
        sourceBucket = sourceBucket,
        sourceKey = sourceKey,
        sourceS3VersionId = sourceVersionId,
        destinationBucket = destinationBucket,
        destinationKey = destinationKey
      )
    }
  }

  /**
    * Read and parse CSV file into a list of S3OperationRequests
    * Supports both local file paths and S3 URIs (s3://bucket/key)
    */
  def readCsvFile(
    csvFilePath: String,
    s3ClientOpt: Option[S3Client] = None
  ): Either[String, List[S3OperationRequest]] = {
    // Determine if we need to download from S3
    val fileToReadEither: Either[String, (File, Boolean)] =
      if (isS3Uri(csvFilePath)) {
        // Parse S3 URI and download
        parseS3Uri(csvFilePath) match {
          case Some((bucket, key)) =>
            s3ClientOpt match {
              case Some(client) =>
                downloadFromS3(bucket, key, client).map(file => (file, true))
              case None =>
                Left("S3 client required to download CSV file from S3")
            }
          case None =>
            Left(
              s"Invalid S3 URI format: $csvFilePath (expected s3://bucket/key)"
            )
        }
      } else {
        // Local file path
        val file = new File(csvFilePath)
        if (!file.exists()) {
          Left(s"CSV file not found: $csvFilePath")
        } else {
          Right((file, false))
        }
      }

    // Read and parse the file
    fileToReadEither.flatMap {
      case (file, isTemporary) =>
        val result = Try {
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
            // Clean up temporary file if it was downloaded from S3
            if (isTemporary) {
              try {
                file.delete()
                logger.debug(
                  s"Cleaned up temporary CSV file: ${file.getAbsolutePath}"
                )
              } catch {
                case ex: Exception =>
                  logger.warn(
                    s"Failed to delete temporary CSV file: ${ex.getMessage}"
                  )
              }
            }
          }
        } match {
          case Success(parseResult) => parseResult
          case Failure(exception) =>
            Left(s"Failed to read CSV file: ${exception.getMessage}")
        }
        result
    }
  }

  /**
    * Execute a DELETE operation
    * If versionId is provided, performs permanent delete. Otherwise, adds a delete marker (soft delete)
    */
  def executeDelete(
    bucket: String,
    key: String,
    versionId: Option[String],
    client: S3Client
  ): Either[Throwable, String] = {
    Try {
      val deleteRequest = {
        val builder = DeleteObjectRequest
          .builder()
          .bucket(bucket)
          .key(key)
          .requestPayer(RequestPayer.REQUESTER)

        versionId.foreach(v => builder.versionId(v))
        builder.build()
      }

      val response = client.deleteObject(deleteRequest)
      val deleteType = if (versionId.isDefined) "permanent" else "soft"
      val versionInfo = versionId.map(v => s" (version: $v)").getOrElse("")
      val marker = if (response.deleteMarker()) " [delete marker added]" else ""

      s"DELETE $deleteType: s3://$bucket/$key$versionInfo$marker"
    } match {
      case Success(msg) => Right(msg)
      case Failure(ex) => Left(ex)
    }
  }

  /**
    * Execute a LIST operation
    * Gets all object attributes, versions, delete markers, and checksums
    */
  def executeList(
    bucket: String,
    key: String,
    versionId: Option[String],
    client: S3Client
  ): Either[Throwable, String] = {
    Try {
      val sb = new StringBuilder()
      sb.append(s"\nLIST: s3://$bucket/$key\n")
      sb.append("=" * 80 + "\n")

      // Get object attributes for current version or specified version
      try {
        val headRequest = {
          val builder = HeadObjectRequest
            .builder()
            .bucket(bucket)
            .key(key)
            .requestPayer(RequestPayer.REQUESTER)
          versionId.foreach(v => builder.versionId(v))
          builder.build()
        }

        val headResponse = client.headObject(headRequest)

        sb.append(
          s"Version ID: ${Option(headResponse.versionId()).getOrElse("null (versioning not enabled)")}\n"
        )
        sb.append(s"Size: ${headResponse.contentLength()} bytes\n")
        sb.append(s"Last Modified: ${headResponse.lastModified()}\n")
        sb.append(s"ETag: ${headResponse.eTag()}\n")
        sb.append(
          s"Content Type: ${Option(headResponse.contentType()).getOrElse("unknown")}\n"
        )
        sb.append(
          s"Storage Class: ${Option(headResponse.storageClassAsString()).getOrElse("STANDARD")}\n"
        )

        // Checksums
        Option(headResponse.checksumSHA256())
          .foreach(sha => sb.append(s"SHA256: $sha\n"))
        Option(headResponse.checksumSHA1())
          .foreach(sha => sb.append(s"SHA1: $sha\n"))
        Option(headResponse.checksumCRC32())
          .foreach(crc => sb.append(s"CRC32: $crc\n"))
        Option(headResponse.checksumCRC32C())
          .foreach(crc => sb.append(s"CRC32C: $crc\n"))

      } catch {
        case ex: Exception =>
          sb.append(s"Error getting object attributes: ${ex.getMessage}\n")
      }

      // List all versions and delete markers
      try {
        val listVersionsRequest = ListObjectVersionsRequest
          .builder()
          .bucket(bucket)
          .prefix(key)
          .requestPayer(RequestPayer.REQUESTER)
          .build()

        val versionsResponse = client.listObjectVersions(listVersionsRequest)

        import scala.jdk.CollectionConverters._
        val versions = versionsResponse.versions().asScala.toList
        val deleteMarkers = versionsResponse.deleteMarkers().asScala.toList

        if (versions.nonEmpty || deleteMarkers.nonEmpty) {
          sb.append("\nAll Versions:\n")
          sb.append("-" * 80 + "\n")

          // Filter to only show versions for this exact key
          val exactVersions = versions.filter(v => v.key() == key)
          exactVersions.foreach { v =>
            sb.append(s"  Version: ${v.versionId()}\n")
            sb.append(s"    Size: ${v.size()} bytes\n")
            sb.append(s"    Last Modified: ${v.lastModified()}\n")
            sb.append(s"    ETag: ${v.eTag()}\n")
            sb.append(s"    Is Latest: ${v.isLatest()}\n")
            sb.append(
              s"    Storage Class: ${Option(v.storageClassAsString()).getOrElse("STANDARD")}\n"
            )
            sb.append("\n")
          }

          val exactMarkers = deleteMarkers.filter(dm => dm.key() == key)
          if (exactMarkers.nonEmpty) {
            sb.append("  Delete Markers:\n")
            exactMarkers.foreach { dm =>
              sb.append(s"    Version: ${dm.versionId()}\n")
              sb.append(s"      Last Modified: ${dm.lastModified()}\n")
              sb.append(s"      Is Latest: ${dm.isLatest()}\n")
              sb.append("\n")
            }
          }
        }
      } catch {
        case ex: Exception =>
          sb.append(s"\nError listing versions: ${ex.getMessage}\n")
      }

      sb.append("=" * 80)
      sb.toString()
    } match {
      case Success(msg) => Right(msg)
      case Failure(ex) => Left(ex)
    }
  }

  /**
    * Execute a KEEP operation
    * Simply checks if the object exists
    */
  def executeKeep(
    bucket: String,
    key: String,
    versionId: Option[String],
    client: S3Client
  ): Either[Throwable, String] = {
    Try {
      val headRequest = {
        val builder = HeadObjectRequest
          .builder()
          .bucket(bucket)
          .key(key)
          .requestPayer(RequestPayer.REQUESTER)
        versionId.foreach(v => builder.versionId(v))
        builder.build()
      }

      val headResponse = client.headObject(headRequest)
      val versionInfo = versionId
        .orElse(Option(headResponse.versionId()))
        .map(v => s" (version: $v)")
        .getOrElse("")
      s"KEEP: s3://$bucket/$key$versionInfo exists (${headResponse.contentLength()} bytes)"
    } match {
      case Success(msg) => Right(msg)
      case Failure(ex) => Left(ex)
    }
  }

  /**
    * Execute any S3 operation based on the request type
    */
  def executeOperation(
    request: S3OperationRequest,
    client: S3Client,
    uploader: MultipartUploader
  )(implicit
    ec: ExecutionContext
  ): Future[Either[Throwable, String]] = {
    request.operation match {
      case S3Operation.COPY =>
        val copyRequest = CopyRequest(
          sourceBucket = request.sourceBucket,
          sourceKey = request.sourceKey,
          sourceS3VersionId = request.sourceS3VersionId,
          destinationBucket = request.destinationBucket.getOrElse(""),
          destinationKey = request.destinationKey.getOrElse("")
        )

        uploader
          .copy(copyRequest)
          .map { result =>
            Right(
              s"COPY: s3://${request.sourceBucket}/${request.sourceKey} → s3://${result.bucket}/${result.key}"
            )
          }
          .recover {
            case ex: Throwable => Left(ex)
          }

      case S3Operation.DELETE =>
        Future {
          executeDelete(
            request.sourceBucket,
            request.sourceKey,
            request.sourceS3VersionId,
            client
          )
        }

      case S3Operation.LIST =>
        Future {
          executeList(
            request.sourceBucket,
            request.sourceKey,
            request.sourceS3VersionId,
            client
          )
        }

      case S3Operation.KEEP =>
        Future {
          executeKeep(
            request.sourceBucket,
            request.sourceKey,
            request.sourceS3VersionId,
            client
          )
        }
    }
  }

  /**
    * Process a batch of operation requests
    */
  def processOperationRequests(
    requests: List[S3OperationRequest],
    client: S3Client,
    uploader: MultipartUploader,
    settings: CsvOpsSettings
  )(implicit
    ec: ExecutionContext
  ): Future[List[Either[Throwable, String]]] = {
    logger.info(s"Processing ${requests.length} S3 operation requests")

    // Process requests with controlled parallelism
    val batches =
      requests.grouped(settings.parallelism).toList

    batches.foldLeft(Future.successful(List.empty[Either[Throwable, String]])) {
      (accFuture, batch) =>
        accFuture.flatMap { acc =>
          // Process this batch in parallel
          val batchFutures = batch.map { request =>
            logger.info(
              s"Executing ${request.operation}: ${request.sourceBucket}/${request.sourceKey}"
            )

            executeOperation(request, client, uploader)
              .map {
                case Right(msg) =>
                  logger.info(msg)
                  Right(msg)
                case Left(ex) =>
                  logger.error(
                    s"Failed to execute ${request.operation}: ${request.sourceBucket}/${request.sourceKey}",
                    ex
                  )
                  Left(ex)
              }
              .recover {
                case ex: Throwable =>
                  logger.error(
                    s"Unexpected error executing ${request.operation}: ${request.sourceBucket}/${request.sourceKey}",
                    ex
                  )
                  Left(ex)
              }
          }

          Future.sequence(batchFutures).map(acc ++ _)
        }
    }
  }

  def printUsage(): Unit = {
    println(
      """
      |Usage: CsvS3OpsMain --csv <path-to-csv> [options]
      |
      |Configuration Priority (highest to lowest):
      |  1. Command line arguments
      |  2. Environment variables
      |  3. Default values
      |
      |Required:
      |  --csv <path>          Path to CSV file with copy instructions
      |                        Supports local file paths and S3 URIs (s3://bucket/key)
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
      |    - operation          : S3 operation to perform (COPY, DELETE, LIST, KEEP)
      |    - source_bucket      : Source S3 bucket name
      |    - source_key         : Source S3 object key
      |    - source_version_id  : (Optional) Source S3 version ID
      |    - destination_bucket : Destination S3 bucket name (required for COPY)
      |    - destination_key    : Destination S3 object key (required for COPY)
      |
      |Operations:
      |  COPY   - Copy object from source to destination (multipart for files ≥5GB)
      |  DELETE - Delete object. Without version ID: soft delete (adds delete marker)
      |           With version ID: permanent delete of specific version
      |  LIST   - List all object attributes, versions, delete markers, checksums
      |  KEEP   - Check that object exists (useful for validation)
      |
      |Example CSV:
      |  operation,source_bucket,source_key,source_version_id,destination_bucket,destination_key
      |  COPY,my-source-bucket,path/to/file1.txt,,my-dest-bucket,new/path/file1.txt
      |  DELETE,my-source-bucket,path/to/old-file.txt,,,
      |  LIST,my-source-bucket,path/to/file2.txt,abc123,,
      |  KEEP,my-source-bucket,path/to/important.txt,,,
      |
      |Example with environment variables:
      |  export CSV_FILE_PATH=/path/to/file.csv
      |  export AWS_REGION=us-west-2
      |  export PARALLELISM=3
      |  sbt "runMain com.pennsieve.publish.CsvS3OpsMain"
      |
      |Example with S3 URI:
      |  sbt "runMain com.pennsieve.publish.CsvS3OpsMain --csv s3://my-bucket/path/to/file.csv"
      |""".stripMargin
    )
  }

  def main(args: Array[String]): Unit = {
    logger.info("CsvS3OpsMain starting")

    if (args.contains("--help") || args.contains("-h")) {
      printUsage()
      sys.exit(0)
    }

    // Load settings: environment variables first, then override with command line args
    val envSettings = CsvOpsSettings.fromEnvironment()
    val settings = CsvOpsSettings.fromArgs(args.toList, envSettings)

    if (settings.csvFilePath.isEmpty) {
      logger.error(
        "CSV file path is required (use --csv or CSV_FILE_PATH environment variable)"
      )
      printUsage()
      sys.exit(1)
    }

    logger.info(s"Settings: $settings")

    // Initialize S3 client (needed for both CSV download and copy operations)
    val client = s3Client(settings.region)
    implicit val ec: ExecutionContext =
      ExecutionContext.fromExecutor(new ForkJoinPool(settings.parallelism))

    try {
      // Read and parse CSV file (supports both local paths and S3 URIs)
      val operationRequestsEither =
        readCsvFile(settings.csvFilePath, Some(client))

      operationRequestsEither match {
        case Left(error) =>
          logger.error(s"Failed to parse CSV file: $error")
          sys.exit(1)

        case Right(operationRequests) =>
          logger.info(
            s"Successfully parsed ${operationRequests.length} S3 operation requests"
          )

          if (operationRequests.isEmpty) {
            logger.warn("No operation requests found in CSV file")
            sys.exit(0)
          }

          // Initialize uploader (needed for COPY operations)
          val uploader = MultipartUploader(client, settings.maxPartSize)

          try {
            // Process all operation requests
            val resultsFuture =
              processOperationRequests(
                operationRequests,
                client,
                uploader,
                settings
              )
            val results = Await.result(resultsFuture, settings.maxWaitTime)

            // Summary
            val successful = results.count(_.isRight)
            val failed = results.count(_.isLeft)

            logger.info(
              s"S3 operations completed: $successful successful, $failed failed"
            )

            if (failed > 0) {
              logger.error("Some S3 operations failed")
              sys.exit(1)
            } else {
              logger.info("All S3 operations completed successfully")
              sys.exit(0)
            }

          } catch {
            case ex: Throwable =>
              logger.error("Unexpected error during copy operations", ex)
              sys.exit(1)
          }
      }
    } finally {
      client.close()
    }
  }
}
