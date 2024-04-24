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
import scala.concurrent.{ Await, ExecutionContext }
import scala.concurrent.duration.{ Duration, FiniteDuration }

object MultipartUploaderMain {
  val UNKNOWN = "unknown"
  val MAX_PART_SIZE: Long = 50 * 1024 * 1024
  val MAX_WAIT_TIME: FiniteDuration = Duration(60, TimeUnit.MINUTES)

  implicit val ec: ExecutionContext =
    scala.concurrent.ExecutionContext.Implicits.global

  def s3Client(settings: Map[String, String]): S3Client = {
    val region = settings.get("--region") match {
      case Some(region) => Region.of(region)
      case None => Region.US_EAST_1
    }

    val sharedHttpClient = UrlConnectionHttpClient.builder().build()

    S3Client.builder
      .region(region)
      .httpClient(sharedHttpClient)
      .build
  }

  def maxPartSize(settings: Map[String, String]): Long =
    settings.get("--maxPartSize") match {
      case Some(value) => value.toLong
      case None => MAX_PART_SIZE
    }

  @tailrec
  def parseArgs(
    args: List[String],
    accum: Map[String, String]
  ): Map[String, String] = {
    args match {
      case h :: t =>
        parseArgs(t.drop(1), accum + (h -> t(0)))
      case _ =>
        accum
    }
  }

  def copyRequest(settings: Map[String, String]) =
    CopyRequest(
      source = ObjectIdentity(
        bucket = settings.getOrElse("--sourceBucket", UNKNOWN),
        key = settings.getOrElse("--sourceKey", UNKNOWN)
      ),
      destination = ObjectIdentity(
        bucket = settings.getOrElse("--destinationBucket", UNKNOWN),
        key = settings.getOrElse("--destinationKey", UNKNOWN)
      )
    )

  def main(args: Array[String]): Unit = {
    println("MultipartUploaderMain.main()")
    val settings = parseArgs(args.toList, Map.empty)
    println(settings)

    val request = copyRequest(settings)
    println(request)

    val s3 = s3Client(settings)
    val partSize = maxPartSize(settings)
    val multipartUploader = MultipartUploader(s3, partSize)
    val resultF = multipartUploader.copy(request)
    val result = Await.result(resultF, MAX_WAIT_TIME)
    println(s"result: ${result}")
  }
}
