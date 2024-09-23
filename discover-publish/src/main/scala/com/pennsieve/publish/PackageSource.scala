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

import akka.NotUsed
import akka.stream.scaladsl.Source
import com.pennsieve.publish.models.PackagePath
import scala.concurrent.{ ExecutionContext, Future }
import scala.collection.mutable.Stack

/**
  * Source that emits all the packages in a dataset.
  */
object PackagesSource {

  def apply(
  )(implicit
    container: PublishContainer,
    ec: ExecutionContext
  ): Source[PackagePath, NotUsed] =
    Source
      .future(
        container.packageManager
          .exportAll(container.dataset)
          .value
          .flatMap {
            case Left(e) => Future.failed(e)
            case Right(packages) =>
              Future.successful(packages.map(p => (p._1, p._2)))
          }
      )
      .flatMapConcat(
        packages =>
          Source
            .unfoldAsync(UnfoldState(new Stack() ++ packages))(emitPackage)
      )

  private def emitPackage(
    state: UnfoldState
  ): Future[Option[(UnfoldState, PackagePath)]] =
    if (state.stack.isEmpty)
      Future.successful(None)
    else {
      val (pkg, path) = state.stack.pop()
      Future.successful(Some((state, (pkg, path))))
    }

  private case class UnfoldState(stack: Stack[PackagePath])
}
