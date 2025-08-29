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

import com.typesafe.scalalogging.LazyLogging
import enumeratum.EnumEntry.UpperSnakecase
import enumeratum._
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  ChecksumAlgorithm,
  CompleteMultipartUploadRequest,
  CompletedMultipartUpload,
  CompletedPart,
  CopyObjectRequest,
  CreateMultipartUploadRequest,
  GetObjectAttributesRequest,
  ObjectAttributes,
  RequestPayer,
  UploadPartCopyRequest
}

import scala.annotation.tailrec
import scala.concurrent.{ ExecutionContext, Future }
import scala.jdk.CollectionConverters._

sealed trait CopyOperation extends EnumEntry with UpperSnakecase

object CopyOperation extends Enum[CopyOperation] with CirceEnum[CopyOperation] {
  val values: IndexedSeq[CopyOperation] = findValues

  case object SinglePartCopy extends CopyOperation
  case object MultipartCopy extends CopyOperation
}

case class CopyRequest(
  sourceBucket: String,
  sourceKey: String,
  destinationBucket: String,
  destinationKey: String
)

case class CompletedRequest(
  bucket: String,
  key: String,
  versionId: String,
  eTag: String,
  sha256: String
)

case class FinishedParts(versionId: String, eTag: String, sha256: String)

class MultipartUploader(s3Client: S3Client, maxPartSize: Long)
    extends LazyLogging {

  private val SinglePartCopyThreshold: Long = 5 * 1024 * 1024 * 1024

  private def getObjectSize(bucket: String, key: String): Long = {
    val getObjectAttributesRequest = GetObjectAttributesRequest
      .builder()
      .bucket(bucket)
      .key(key)
      .requestPayer(RequestPayer.REQUESTER)
      .objectAttributes(List(ObjectAttributes.OBJECT_SIZE).asJava)
      .build()
    val getObjectAttributesResponse = {
      s3Client.getObjectAttributes(getObjectAttributesRequest)
    }
    val objectSize = getObjectAttributesResponse.objectSize()
    logger.debug(
      s"MultipartUploader.getObjectSize() bucket: ${bucket} key: ${key} objectSize: ${objectSize}"
    )
    objectSize
  }

  private def byteRange(offset: Long, size: Long): String =
    s"bytes=${offset}-${offset + size - 1}"

  @tailrec
  private def parts(
    offset: Long,
    objectSize: Long,
    partSize: Long,
    accumulator: List[String]
  ): List[String] =
    if (objectSize <= partSize)
      byteRange(offset, objectSize) :: accumulator
    else
      parts(
        offset + partSize,
        objectSize - partSize,
        partSize,
        byteRange(offset, partSize) :: accumulator
      )

  private def start(bucket: String, key: String): String = {
    val createMultipartUploadRequest = CreateMultipartUploadRequest
      .builder()
      .checksumAlgorithm(ChecksumAlgorithm.SHA256)
      .bucket(bucket)
      .key(key)
      .requestPayer(RequestPayer.REQUESTER)
      .build()

    val createMultipartUploadResponse =
      s3Client.createMultipartUpload(createMultipartUploadRequest)
    createMultipartUploadResponse.uploadId()
  }

  private def copyPart(
    sourceBucket: String,
    sourceKey: String,
    destinationBucket: String,
    destinationKey: String,
    uploadId: String,
    index: Int,
    part: String
  ): (Int, CompletedPart) = {
    logger.debug(
      s"MultipartUploader.copyPart() uploadId: ${uploadId} index: ${index} part: ${part}"
    )
    val uploadPartCopyRequest = UploadPartCopyRequest
      .builder()
      .uploadId(uploadId)
      .sourceBucket(sourceBucket)
      .sourceKey(sourceKey)
      .destinationBucket(destinationBucket)
      .destinationKey(destinationKey)
      .copySourceRange(part)
      .partNumber(index)
      .requestPayer(RequestPayer.REQUESTER)
      .build()

    val uploadPartCopyResponse =
      s3Client.uploadPartCopy(uploadPartCopyRequest)
    val copyPartResult = uploadPartCopyResponse.copyPartResult()

    val completedPart = CompletedPart
      .builder()
      .eTag(copyPartResult.eTag())
      .checksumSHA256(copyPartResult.checksumSHA256())
      .partNumber(index)
      .build()

    (index, completedPart)
  }

  private def finish(
    bucket: String,
    key: String,
    uploadId: String,
    completedParts: Seq[CompletedPart]
  ): FinishedParts = {
    val completedMultipartUpload = CompletedMultipartUpload
      .builder()
      .parts(completedParts.asJava)
      .build()

    val completeMultipartUploadRequest = CompleteMultipartUploadRequest
      .builder()
      .bucket(bucket)
      .key(key)
      .uploadId(uploadId)
      .multipartUpload(completedMultipartUpload)
      .requestPayer(RequestPayer.REQUESTER)
      .build()

    val completeMultipartUploadResponse =
      s3Client.completeMultipartUpload(completeMultipartUploadRequest)
    FinishedParts(
      versionId = completeMultipartUploadResponse.versionId(),
      eTag = completeMultipartUploadResponse.eTag(),
      sha256 = completeMultipartUploadResponse.checksumSHA256()
    )
  }

  def multipartCopy(
    objectSize: Long,
    request: CopyRequest
  )(implicit
    ec: ExecutionContext
  ): Future[CompletedRequest] =
    Future {
      val partList = parts(0L, objectSize, maxPartSize, List[String]()).reverse
      val uploadId = start(request.destinationBucket, request.destinationKey)
      logger.debug(
        s"MultipartUploader.multipartCopy() uploadId: ${uploadId} numberOfParts: ${partList.length}"
      )
      val copiedParts = partList.zipWithIndex.map {
        case (part, index) =>
          copyPart(
            request.sourceBucket,
            request.sourceKey,
            request.destinationBucket,
            request.destinationKey,
            uploadId,
            index + 1,
            part
          )
      }
      val finishedParts =
        finish(
          request.destinationBucket,
          request.destinationKey,
          uploadId,
          copiedParts.map(_._2)
        )

      CompletedRequest(
        bucket = request.destinationBucket,
        key = request.destinationKey,
        versionId = finishedParts.versionId,
        eTag = finishedParts.eTag,
        sha256 = finishedParts.sha256
      )
    }

  private def singlePartCopy(
    request: CopyRequest
  )(implicit
    ec: ExecutionContext
  ): Future[CompletedRequest] = Future {
    logger.debug(
      s"MultipartUploader.singlePartCopy() ${request.sourceBucket}/${request.sourceKey} -> ${request.destinationBucket}/${request.destinationKey}"
    )

    val copyObjectRequest = CopyObjectRequest
      .builder()
      .sourceBucket(request.sourceBucket)
      .sourceKey(request.sourceKey)
      .destinationBucket(request.destinationBucket)
      .destinationKey(request.destinationKey)
      .requestPayer(RequestPayer.REQUESTER)
      .checksumAlgorithm(ChecksumAlgorithm.SHA256)
      .build()

    val copyObjectResponse = s3Client.copyObject(copyObjectRequest)
    val copyObjectResult = copyObjectResponse.copyObjectResult()

    CompletedRequest(
      bucket = request.destinationBucket,
      key = request.destinationKey,
      versionId = copyObjectResponse.versionId(),
      eTag = copyObjectResult.eTag(),
      sha256 = copyObjectResult.checksumSHA256()
    )
  }

  private def copyOperation(objectSize: Long): CopyOperation =
    if (objectSize >= SinglePartCopyThreshold) {
      CopyOperation.MultipartCopy
    } else {
      CopyOperation.SinglePartCopy
    }

  def copy(
    request: CopyRequest
  )(implicit
    ec: ExecutionContext
  ): Future[CompletedRequest] = {
    logger.debug(s"MultipartUploader.copy() request: ${request}")

    val startTime = System.nanoTime()
    val objectSize =
      getObjectSize(request.sourceBucket, request.sourceKey)

    val completedRequestF = copyOperation(objectSize) match {
      case CopyOperation.SinglePartCopy => singlePartCopy(request)
      case CopyOperation.MultipartCopy => multipartCopy(objectSize, request)
    }

    for {
      completedRequest <- completedRequestF

      finishTime = System.nanoTime()
      _ = logger.debug(
        s"MultipartUploader.copy() elapsed: ${finishTime - startTime} completedRequest: ${completedRequest}"
      )

    } yield completedRequest
  }
}

object MultipartUploader {
  def apply(s3Client: S3Client, maxPartSize: Long) =
    new MultipartUploader(s3Client, maxPartSize)
}
