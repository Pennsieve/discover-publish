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

import org.scalatest.{ Assertion, Assertions, EitherValues }
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.{
  BucketVersioningStatus,
  ChecksumMode,
  Delete,
  DeleteBucketRequest,
  DeleteMarkerEntry,
  DeleteObjectRequest,
  DeleteObjectsRequest,
  GetBucketVersioningRequest,
  GetObjectRequest,
  GetObjectResponse,
  ListObjectVersionsRequest,
  ListObjectsV2Request,
  ObjectIdentifier,
  ObjectVersion,
  PutBucketVersioningRequest,
  PutObjectRequest,
  PutObjectResponse,
  S3Object,
  VersioningConfiguration
}
import cats.implicits._
import com.pennsieve.models.{
  DatasetMetadataV5_0,
  FileManifest,
  FileType,
  NodeCodes
}
import io.circe.Encoder
import software.amazon.awssdk.core.sync.RequestBody

import java.security.MessageDigest
import java.util.{ Base64, UUID }
import scala.jdk.CollectionConverters._
import io.circe.syntax._

/**
  * Helpers for dealing with S3 in tests. Only use the provided AWS SDK V2 client.
  * No V1 client use here!
  */
trait S3Helper extends EitherValues with Assertions {
  private var _s3Client: S3Client = _

  def s3Client: S3Client = {
    require(
      _s3Client != null,
      "s3Client not initialized — was initS3Helper() called?"
    )
    _s3Client
  }

  protected def initS3Helper(client: S3Client): Unit = {
    _s3Client = client
  }

  /**
    * Run prior to publishAssets to clean `${bucket}/${publishS3Prefix}` of existing objects before
    * starting the publishing process. This is used to simulate discover-s3clean behavior prior to the discover-publish
    * step function workflow being invoked.
    *
    * @param bucket          the publish bucket
    * @param publishS3Prefix the s3 key prefix to be cleaned
    * @return
    */
  def s3PrePublishCleanObjects(
    bucket: String,
    publishS3Prefix: String
  ): Unit = {
    val (versions, deleteMarkers) =
      listVersionedBucket(bucket, Some(publishS3Prefix))
    deleteVersionsAndMarkers(bucket, versions, deleteMarkers)
  }

  def enableBucketVersioning(bucketName: String): Either[Throwable, Unit] = {
    Either.catchNonFatal {
      s3Client.putBucketVersioning(
        PutBucketVersioningRequest
          .builder()
          .bucket(bucketName)
          .versioningConfiguration(
            VersioningConfiguration
              .builder()
              .status(BucketVersioningStatus.ENABLED)
              .build()
          )
          .build()
      )
    }
  }

  /**
    * Delete all objects from bucket, and delete the bucket itself
    */
  def deleteBucket(bucket: String): Assertion = {
    val versioningConfig = s3Client.getBucketVersioning(
      GetBucketVersioningRequest.builder().bucket(bucket).build()
    )
    if (versioningConfig.status() == BucketVersioningStatus.ENABLED) {
      deleteVersionedBucket(bucket)
    } else {
      deleteUnversionedBucket(bucket)
    }

  }

  def deleteUnversionedBucket(bucket: String): Assertion = {
    val listAndDeleteResult = Either
      .catchNonFatal {
        listBucket(bucket)
          .map { o =>
            s3Client
              .deleteObject(
                DeleteObjectRequest
                  .builder()
                  .bucket(bucket)
                  .key(o.key())
                  .build()
              )
          }
      }
    require(
      listAndDeleteResult.isRight,
      s"error deleting objects from bucket $bucket: ${listAndDeleteResult.left}"
    )
    deleteEmptyBucket(bucket)
  }

  def listBucket(bucket: String): List[S3Object] =
    s3Client
      .listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
      .contents()
      .asScala
      .toList

  def deleteVersionsAndMarkers(
    bucket: String,
    versions: List[ObjectVersion],
    deleteMarkers: List[DeleteMarkerEntry]
  ): Unit = {
    val ids = versions.map(
      v =>
        ObjectIdentifier.builder().key(v.key()).versionId(v.versionId()).build()
    ) ++
      deleteMarkers.map(
        d =>
          ObjectIdentifier
            .builder()
            .key(d.key())
            .versionId(d.versionId())
            .build()
      )

    if (ids.nonEmpty) {
      val deleteObjectsResult = Either.catchNonFatal(
        s3Client.deleteObjects(
          DeleteObjectsRequest
            .builder()
            .bucket(bucket)
            .delete(Delete.builder().objects(ids.asJava).build())
            .build()
        )
      )
      assert(
        deleteObjectsResult.isRight,
        s"Delete objects failed on bucket $bucket: ${deleteObjectsResult.left}"
      )

    }
  }

  def deleteVersionedBucket(bucket: String): Assertion = {
    val (versions, deleteMarkers) =
      Either.catchNonFatal(listVersionedBucket(bucket)).value
    deleteVersionsAndMarkers(bucket, versions, deleteMarkers)
    deleteEmptyBucket(bucket)
  }

  def listVersionedBucket(
    bucket: String,
    prefix: Option[String] = None
  ): (List[ObjectVersion], List[DeleteMarkerEntry]) = {
    val requestBuilder = ListObjectVersionsRequest.builder().bucket(bucket)
    if (prefix.nonEmpty) {
      requestBuilder.prefix(prefix.get)
    }
    val versionsResponse = s3Client
      .listObjectVersions(requestBuilder.build())

    (
      versionsResponse.versions().asScala.toList,
      versionsResponse.deleteMarkers().asScala.toList
    )
  }

  def deleteEmptyBucket(bucket: String): Assertion = {
    val deleteBucketResult = Either.catchNonFatal(
      s3Client
        .deleteBucket(DeleteBucketRequest.builder().bucket(bucket).build())
    )
    assert(
      deleteBucketResult.isRight,
      s"delete empty bucket $bucket failed: ${deleteBucketResult.left}"
    )
  }

  // putS3File puts an object into the given bucket and key using the SDK V2 client
  // so that it can request ChecksumAlgorithm.SHA256, not available in SDK V1 client.
  def putS3File(
    s3Bucket: String,
    s3Key: String,
    content: String = UUID.randomUUID().toString
  ): PutObjectResponse = {
    val contentBytes = content.getBytes("UTF-8")

    // Calculate SHA256 checksum since both minio and localstack
    // report checksum mismatch if we just use .checksumAlgorithm(ChecksumAlgorithm.SHA256)
    // to let the SDK compute the hash.
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(contentBytes)
    val checksumSHA256 = Base64.getEncoder.encodeToString(hash)
    val request = PutObjectRequest
      .builder()
      .bucket(s3Bucket)
      .key(s3Key)
      //.checksumAlgorithm(ChecksumAlgorithm.SHA256)
      .checksumSHA256(checksumSHA256)
      .build()

    s3Client.putObject(request, RequestBody.fromString(content))
  }

  def uploadPublishedPackage(
    s3Bucket: String,
    publishS3Prefix: String,
    name: String,
    path: String,
    sourcePackageId: String = NodeCodes.generateId(NodeCodes.packageCode),
    fileType: FileType = FileType.Data,
    content: String = UUID.randomUUID().toString
  ): FileManifest = {
    val size = content.getBytes("UTF-8").length

    val s3Key = utils.joinKeys(publishS3Prefix, path)
    val putResponse =
      putS3File(s3Bucket = s3Bucket, s3Key = s3Key, content = content)

    val s3VersionId = Option(putResponse.versionId())
    val sha256 = Option(putResponse.checksumSHA256())
    FileManifest(
      name = name,
      size = size,
      fileType = fileType,
      path = path,
      s3VersionId = s3VersionId,
      sha256 = sha256,
      sourcePackageId = Some(sourcePackageId)
    )
  }

  def uploadManifest(
    s3Bucket: String,
    publishS3Prefix: String,
    manifest: DatasetMetadataV5_0
  )(implicit
    encoder: Encoder[DatasetMetadataV5_0]
  ): Assertion = {
    val manifestJSON = manifest.asJson.toString()
    val s3Key = publishS3Prefix + Publish.MANIFEST_FILENAME
    val putResult =
      Either.catchNonFatal(putS3File(s3Bucket, s3Key, manifestJSON))
    assert(
      putResult.isRight,
      s"error uploading manifest to $s3Bucket $s3Key: ${putResult.left}"
    )
  }

  /**
    * Read file contents from S3 as a string.
    */
  def downloadFile(s3Bucket: String, s3Key: String): String =
    downloadContentAndObject(s3Bucket, s3Key)._1

  def downloadContentAndObject(
    s3Bucket: String,
    s3Key: String
  ): (String, GetObjectResponse) = {

    val responseInputStream = s3Client.getObject(
      GetObjectRequest
        .builder()
        .bucket(s3Bucket)
        .key(s3Key)
        .checksumMode(ChecksumMode.ENABLED)
        .build()
    )
    try {
      val content =
        scala.io.Source.fromInputStream(responseInputStream, "UTF-8").mkString
      (content, responseInputStream.response())
    } finally {
      responseInputStream.close()
    }
  }

}
