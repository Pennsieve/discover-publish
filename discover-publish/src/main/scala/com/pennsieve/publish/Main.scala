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

import akka.actor.ActorSystem
import com.typesafe.scalalogging.StrictLogging
import com.typesafe.config.{ Config, ConfigFactory }
import cats.implicits._
import com.pennsieve.utilities.AbstractError
import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.pennsieve.aws.s3.S3
import net.ceedubs.ficus.Ficus._

import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.services.s3.S3Client

import io.circe.{ HCursor, Json }
import io.circe.parser.parse

import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration._
import scala.io.Source
import akka.dispatch.MessageDispatcher
import cats.data.EitherT
import com.pennsieve.domain.CoreError

case class PublishError(message: String) extends AbstractError {
  final override def getMessage: String = message
}

sealed trait PublishAction
case object PublishAssets extends PublishAction
case object Finalize extends PublishAction

object Main extends App with StrictLogging {

  def getEnv(key: String): Either[PublishError, String] =
    sys.env
      .get(key)
      .map(value => {
        logger.info(s"$key: $value")
        value
      })
      .toRight(PublishError(s"Missing key '$key'"))

  def getPublishAction(cmd: String): Either[PublishError, PublishAction] =
    cmd.trim.toLowerCase() match {
      case "publish-assets" => Right(PublishAssets)
      case "finalize" => Right(Finalize)
      case _ => Left(PublishError(s"Not a value command: ${cmd}"))
    }

  def jsonType(json: Json): String =
    json.fold(
      "null",
      _ => "boolean",
      _ => "number",
      _ => "string",
      _ => "array",
      _ => "object"
    )

  def readPublishInput(
    s3: S3,
    bucket: String,
    key: String
  ): Either[PublishError, HCursor] = {
    def fail(reason: String): PublishError =
      PublishError(s"Publish input 's3://$bucket/$key' $reason")

    for {
      body <- s3
        .getObject(bucket, key)
        .flatMap(
          obj =>
            Either.catchNonFatal(
              Source.fromInputStream(obj.getObjectContent).mkString
            )
        )
        .leftMap(t => fail(s"could not be read: ${t.getMessage}"))
      json <- parse(body).leftMap(
        err => fail(s"is not valid JSON: ${err.getMessage}")
      )
      // A file encoded twice parses as a JSON string, which would otherwise be
      // reported as every field being missing.
      cursor <- Either.cond(
        json.isObject,
        json.hcursor,
        fail(s"is a JSON ${jsonType(json)}, not an object")
      )
    } yield cursor
  }

  /**
    * Every field of the publish input is a string: the caller stringifies integers and
    * booleans because ECS container overrides can only carry strings.
    */
  def getField(cursor: HCursor, key: String): Either[PublishError, String] = {
    val field = cursor.downField(key)

    field.as[String] match {
      case Right(value) =>
        logger.info(s"$key: $value")
        Right(value)
      case Left(_) =>
        Left(field.focus match {
          case None => PublishError(s"Missing key '$key' in publish input")
          case Some(json) =>
            PublishError(
              s"Key '$key' in publish input is a ${jsonType(json)}, not a string"
            )
        })
    }
  }

  logger.info(s"Discover-Publish: Starting")
  try {
    val start: Long = System.currentTimeMillis / 1000

    val config: Config = ConfigFactory.load()

    implicit lazy val system: ActorSystem = ActorSystem("discover-publish")
    implicit lazy val executionContext: ExecutionContext =
      system.dispatcher

    /**
      * Use Pennsieve S3 client wrapper.
      */
    val s3: S3 = {
      val s3Region: Regions =
        config.as[Option[String]]("s3.region") match {
          case Some(region) => Regions.fromName(region)
          case None => Regions.US_EAST_1
        }

      val clientConfig =
        new ClientConfiguration().withSignerOverride("AWSS3V4SignerType")

      new S3(
        AmazonS3ClientBuilder
          .standard()
          .withClientConfiguration(clientConfig)
          .withCredentials(DefaultAWSCredentialsProviderChain.getInstance())
          .withRegion(s3Region)
          .build()
      )
    }

    val s3Client: S3Client = {
      val region = config.as[Option[String]]("s3.region") match {
        case Some(region) => Region.of(region)
        case None => Region.US_EAST_1
      }

      val sharedHttpClient = UrlConnectionHttpClient.builder().build()

      S3Client.builder
        .region(region)
        .httpClient(sharedHttpClient)
        .build
    }

    val result: Either[AbstractError, Unit] = for {
      publishAction <- getEnv("PUBLISH_ACTION").flatMap(getPublishAction(_))

      inputBucket <- getEnv("PUBLISH_INPUT_BUCKET")
      inputKey <- getEnv("PUBLISH_INPUT_KEY")
      input <- readPublishInput(s3, inputBucket, inputKey)

      userId <- getField(input, "user_id").map(_.toInt)
      userFirstName <- getField(input, "user_first_name")
      userLastName <- getField(input, "user_last_name")
      userOrcid <- getField(input, "user_orcid")
      userNodeId <- getField(input, "user_node_id")
      organizationId <- getField(input, "organization_id").map(_.toInt)
      organizationNodeId <- getField(input, "organization_node_id")
      organizationName <- getField(input, "organization_name")
      datasetId <- getField(input, "dataset_id").map(_.toInt)
      datasetNodeId <- getField(input, "dataset_node_id")
      publishedDatasetId <- getField(input, "published_dataset_id").map(_.toInt)
      version <- getField(input, "version").map(_.toInt)
      contributors <- getField(input, "contributors")
      collections <- getField(input, "collections")
      externalPublications <- getField(input, "external_publications")
      doi <- getField(input, "doi")

      s3Bucket <- getField(input, "s3_bucket") // either the publish or embargo bucket
      s3Key <- getField(input, "s3_publish_key")

      workflowId <- getField(input, "workflow_id").map(_.toLong)

      expectPrevious <- getField(input, "expect_previous").map(_.toBoolean)

      publishContainer = Await.result(
        PublishContainer.secureContainer(
          config = config,
          s3 = s3,
          s3Client = s3Client,
          s3Key = s3Key,
          s3Bucket = s3Bucket,
          doi = doi,
          datasetId = datasetId,
          datasetNodeId = datasetNodeId,
          publishedDatasetId = publishedDatasetId,
          version = version,
          userId = userId,
          userNodeId = userNodeId,
          userFirstName = userFirstName,
          userLastName = userLastName,
          userOrcid = userOrcid,
          organizationId = organizationId,
          organizationNodeId = organizationNodeId,
          organizationName = organizationName,
          contributors = contributors,
          collections = collections,
          externalPublications = externalPublications,
          workflowId = workflowId,
          expectPrevious = expectPrevious
        ),
        10 seconds
      )

      action = publishAction match {
        case PublishAssets =>
          logger.info(s"Publishing Assets")
          Publish.publishAssets(publishContainer)
        case Finalize =>
          logger.info(s"Finalizing Publication")
          Publish.finalizeDataset(publishContainer)
      }

      result <- Await.result(action.value, 48.hour)

    } yield result

    result.valueOr(ex => throw ex)

    val end: Long = System.currentTimeMillis / 1000
    val elapsed = end - start
    logger.info(s"Discover-Publish: Completed elapsed: $elapsed seconds")

  } catch {
    case ex: Throwable =>
      logger.error("Publish failed", ex)
      sys.exit(1)
  }

  sys.exit(0)
}
