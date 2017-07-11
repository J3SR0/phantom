/*
 * Copyright 2013 - 2017 Outworkers Ltd.
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
package com.outworkers.phantom.builder.query.execution

import cats.Monad
import com.datastax.driver.core.{Session, SimpleStatement, Statement}
import com.outworkers.phantom.ResultSet
import com.outworkers.phantom.builder.query.engine.CQLQuery

import scala.collection.generic.CanBuildFrom
import scala.concurrent.ExecutionContextExecutor

trait GuavaAdapter[F[_]] {
  def fromGuava(in: Statement)(
    implicit session: Session,
    ctx: ExecutionContextExecutor
  ): F[ResultSet]
}

class ExecutableStatementList[
  F[_],
  M[X] <: TraversableOnce[X]
](val queries: M[CQLQuery])(
  implicit cbf: CanBuildFrom[M[CQLQuery], CQLQuery, M[CQLQuery]],
  fMonad: Monad[F],
  adapter: GuavaAdapter[F]
) {

  def isEmpty: Boolean = queries.isEmpty

  def add(appendable: M[CQLQuery]): ExecutableStatementList[F, M] = {
    val builder = cbf(queries)
    for (q <- appendable) builder += q
    new ExecutableStatementList(builder.result())
  }

  def sequencedTraverse[A, B](in: M[A])(fn: A => F[B])(
    implicit executor: ExecutionContextExecutor,
    cbf: CanBuildFrom[M[A], B, M[B]]
  ): F[M[B]] = {
    in.foldLeft(fMonad.pure(cbf(in))) { (fr, a) =>
      for (r <- fr; b <- fn(a)) yield r += b
    }.map(_.result())
  }

  /**
    * Simple version of `Future.traverse`. Asynchronously and non-blockingly transforms a `TraversableOnce[Future[A]]`
    *  into a `Future[TraversableOnce[A]]`. Useful for reducing many `Future`s into a single `Future`.
    *
    * @tparam A        the type of the value inside the Futures
    * @param in        the `TraversableOnce` of Futures which will be sequenced
    * @return          the `Future` of the `TraversableOnce` of results
    */
  def parallel[A](in: M[F[A]])(
    implicit cbf: CanBuildFrom[M[F[A]], A, M[A]],
    executor: ExecutionContextExecutor
  ): F[M[A]] = {
    in.foldLeft(fMonad.pure(cbf(in))) { (fr, fa) =>
      fr.zipWith(fa)(_ += _)
    }.map(_.result())(executor)
  }

  def ++(appendable: M[CQLQuery]): ExecutableStatementList[F, M] = add(appendable)

  def ++(st: ExecutableStatementList[F, M]): ExecutableStatementList[F, M] = add(st.queries)

  /**
    *
    * @param session
    * @param ec
    * @param fbf
    * @param ebf
    * @return
    */
  def future()(
    implicit session: Session,
    ec: ExecutionContextExecutor,
    fbf: CanBuildFrom[Nothing, F[ResultSet], M[F[ResultSet]]],
    ebf: CanBuildFrom[M[F[ResultSet]], ResultSet, M[ResultSet]]
  ): F[M[ResultSet]] = {

    val builder = fbf()

    for (q <- queries) builder += adapter.fromGuava(new SimpleStatement(q.terminate.queryString))

    parallel(builder.result())(ebf, ec)
  }

  def sequence()(
    implicit session: Session,
    ec: ExecutionContextExecutor,
    cbf: CanBuildFrom[M[CQLQuery], ResultSet, M[ResultSet]]
  ): F[M[ResultSet]] = {
    sequencedTraverse(queries) {
      q => adapter.fromGuava(new SimpleStatement(q.terminate.queryString))
    }
  }
}
