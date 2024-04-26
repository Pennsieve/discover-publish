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

import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client

import java.util.concurrent.TimeUnit
import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.duration.{ Duration, FiniteDuration, SECONDS }
import scala.concurrent.ExecutionContext.Implicits.global

sealed trait MultipartOperation
object MultipartOperation {
  case object COPY extends MultipartOperation
  case object UPLOAD extends MultipartOperation
  case object INVALID extends MultipartOperation

  def fromString(string: String): MultipartOperation =
    string.toUpperCase() match {
      case "COPY" => MultipartOperation.COPY
      case "UPLOAD" => MultipartOperation.UPLOAD
      case _ => MultipartOperation.INVALID
    }
}

case class Settings(
  action: MultipartOperation = MultipartOperation.INVALID,
  region: Region = Settings.DEFAULT_REGION,
  maxPartSize: Long = Settings.MAX_PART_SIZE,
  maxWaitTime: Duration = Settings.MAX_WAIT_TIME,
  sourceBucket: Option[String] = None,
  sourceKey: Option[String] = None,
  destinationBucket: Option[String] = None,
  destinationKey: Option[String] = None
) {
  def withSetting(name: String, value: String): Settings =
    name match {
      case "--action" =>
        this.copy(action = MultipartOperation.fromString(value))
      case "--region" =>
        this.copy(region = Region.of(value))
      case "--maxPartSize" =>
        this.copy(maxPartSize = value.toLong)
      case "--maxWaitTime" =>
        this.copy(
          maxWaitTime = FiniteDuration(Duration(value).toSeconds, SECONDS)
        )
      case "--sourceBucket" =>
        this.copy(sourceBucket = Some(value))
      case "--sourceKey" =>
        this.copy(sourceKey = Some(value))
      case "--destinationBucket" =>
        this.copy(destinationBucket = Some(value))
      case "--destinationKey" =>
        this.copy(destinationKey = Some(value))
      case _ => // ignore any unrecognized names
        this
    }
}

object Settings {
  val DEFAULT_REGION: Region = Region.US_EAST_1
  val MAX_PART_SIZE: Long = 50 * 1024 * 1024
  val MAX_WAIT_TIME: FiniteDuration = Duration(60, TimeUnit.MINUTES)

  def apply(): Settings = new Settings()

  @tailrec
  def fromArgs(args: List[String], settings: Settings): Settings = {
    args match {
      case h :: t =>
        Settings.fromArgs(t.drop(1), settings.withSetting(h, t(0)))
      case _ =>
        settings
    }
  }
}

object MultipartUploaderMain {

  def s3Client(region: Region): S3Client = {
    val sharedHttpClient = UrlConnectionHttpClient.builder().build()
    S3Client.builder
      .region(region)
      .httpClient(sharedHttpClient)
      .build
  }

  def copyRequest(settings: Settings): Option[CopyRequest] =
    (
      settings.sourceBucket,
      settings.sourceKey,
      settings.destinationBucket,
      settings.destinationKey
    ) match {
      case (
          Some(sourceBucket: String),
          Some(sourceKey: String),
          Some(destinationBucket: String),
          Some(destinationKey: String)
          ) =>
        Some(
          CopyRequest(
            sourceBucket = sourceBucket,
            sourceKey = sourceKey,
            destinationBucket = destinationBucket,
            destinationKey = destinationKey
          )
        )
      case (_, _, _, _) => None
    }

  def copy(settings: Settings): Unit =
    copyRequest(settings) match {
      case Some(request: CopyRequest) =>
        val result = Await.result(
          MultipartUploader(s3Client(settings.region), settings.maxPartSize)
            .copy(request),
          settings.maxWaitTime
        )
        println(s"result: ${result}")
      case None =>
        println("invalid copy request")
    }

  def main(args: Array[String]): Unit = {
    println("MultipartUploaderMain.main()")
    val settings = Settings.fromArgs(args.toList, Settings())
    println(settings)

    settings.action match {
      case MultipartOperation.COPY =>
        copy(settings)
      case _ =>
        println(s"action '${settings.action}' not supported")
    }
  }
}
