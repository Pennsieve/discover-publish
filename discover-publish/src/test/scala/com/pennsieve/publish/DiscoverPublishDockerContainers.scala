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

import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.{ AWSStaticCredentialsProvider, BasicAWSCredentials }
import com.amazonaws.services.s3.{ AmazonS3, AmazonS3ClientBuilder }
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration
import com.dimafeng.testcontainers.GenericContainer
import com.pennsieve.test.{
  DockerContainer,
  PostgresContainerImpl,
  PostgresDockerContainerImpl,
  StackedDockerContainer
}
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy
import com.typesafe.config.{ Config, ConfigFactory, ConfigValueFactory }
import software.amazon.awssdk.auth.credentials.{
  AwsBasicCredentials,
  StaticCredentialsProvider
}
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.{ S3Client, S3Configuration }

import java.net.URI

/**
  * Shared singleton Docker containers.
  *
  * Containers are cached on this object so that the same container can be used
  * across multiple test suites by the PersistantDockerContainers trait.
  */
object DiscoverPublishDockerContainers {
  val postgresContainer: PostgresContainerImpl =
    new PostgresDockerContainerImpl

  val s3Container: S3DockerContainerImpl =
    new S3DockerContainerImpl

  val mockServerContainer: MockServerDockerContainerImpl =
    new MockServerDockerContainerImpl

}

object S3DockerContainer {
  val port: Int = 4566
  val accessKey: String = "access-key"
  val secretKey: String = "secret-key"
}

final class S3DockerContainerImpl
    extends DockerContainer(
      dockerImage = s"localstack/localstack:stable",
      exposedPorts = Seq(S3DockerContainer.port),
      env = Map(
        "SERVICES" -> "s3",
        "AWS_DEFAULT_REGION" -> "us-east-1",
        "AWS_ACCESS_KEY_ID" -> S3DockerContainer.accessKey,
        "AWS_SECRET_ACCESS_KEY" -> S3DockerContainer.secretKey,
        "PERSISTENCE" -> "0",
        "DEBUG" -> "0" // change to "1" for more logging in Docker container
      ),
      waitStrategy = Some(new HttpWaitStrategy().forPath("/_localstack/health"))
    ) {

  def mappedPort(): Int = super.mappedPort(S3DockerContainer.port)

  val accessKey: String = S3DockerContainer.accessKey
  val secretKey: String = S3DockerContainer.secretKey

  def endpointUrl: String = s"http://${containerIpAddress}:${mappedPort()}"

  def apply(): GenericContainer = this

  override def config: Config =
    ConfigFactory
      .empty()
      .withValue(
        "alpakka.s3.endpoint-url",
        ConfigValueFactory.fromAnyRef(endpointUrl)
      )
      .withValue(
        "alpakka.s3.path-style-access",
        ConfigValueFactory.fromAnyRef(true)
      )
      .withValue(
        "alpakka.s3.aws.credentials.provider",
        ConfigValueFactory.fromAnyRef("static")
      )
      .withValue(
        "alpakka.s3.aws.credentials.access-key-id",
        ConfigValueFactory.fromAnyRef(accessKey)
      )
      .withValue(
        "alpakka.s3.aws.credentials.secret-access-key",
        ConfigValueFactory.fromAnyRef(secretKey)
      )
      .withValue(
        "alpakka.s3.aws.region.provider",
        ConfigValueFactory.fromAnyRef("static")
      )
      .withValue(
        "alpakka.s3.aws.region.default-region",
        ConfigValueFactory.fromAnyRef("us-east-1")
      )

  def s3Client: AmazonS3 = {
    val creds = new BasicAWSCredentials(accessKey, secretKey)
    val credsProvider = new AWSStaticCredentialsProvider(creds)
    val endpoint = new EndpointConfiguration(endpointUrl, "us-east-1")
    val clientConfig =
      new ClientConfiguration().withSignerOverride("AWSS3V4SignerType")
    AmazonS3ClientBuilder
      .standard()
      .withCredentials(credsProvider)
      .withEndpointConfiguration(endpoint)
      .withPathStyleAccessEnabled(true)
      .withClientConfiguration(clientConfig)
      .build()
  }

  def s3ClientV2: S3Client =
    S3Client
      .builder()
      .endpointOverride(URI.create(endpointUrl))
      .region(Region.US_EAST_1)
      .credentialsProvider(
        StaticCredentialsProvider.create(
          AwsBasicCredentials
            .create(accessKey, secretKey)
        )
      )
      .serviceConfiguration(
        S3Configuration
          .builder()
          .pathStyleAccessEnabled(true) // seems necessary for localstack
          .build()
      )
      .build()
}

trait DiscoverPublishS3DockerContainer extends StackedDockerContainer {
  val s3Container = DiscoverPublishDockerContainers.s3Container

  override def stackedContainers = s3Container :: super.stackedContainers
}

trait DiscoverPublishPostgresDockerContainer extends StackedDockerContainer {
  val postgresContainer = DiscoverPublishDockerContainers.postgresContainer

  override def stackedContainers = postgresContainer :: super.stackedContainers
}
