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
package com.outworkers.phantom.builder.query

import com.datastax.driver.core.{ConsistencyLevel, Session}
import com.outworkers.phantom.{CassandraTable, Row}
import com.outworkers.phantom.builder._
import com.outworkers.phantom.builder.clauses._
import com.outworkers.phantom.builder.query.engine.CQLQuery
import com.outworkers.phantom.builder.query.execution.ExecutableStatement
import com.outworkers.phantom.builder.query.prepared.{PrepareMark, PreparedBlock}
import com.outworkers.phantom.connectors.KeySpace
import com.outworkers.phantom.dsl.DateTime
import shapeless.ops.hlist.{Prepend, Reverse}
import shapeless.{::, =:!=, HList, HNil}

import scala.concurrent.duration.{FiniteDuration => ScalaDuration}

class UpdateQuery[
  Table <: CassandraTable[Table, _],
  Record,
  Limit <: LimitBound,
  Order <: OrderBound,
  Status <: ConsistencyBound,
  Chain <: WhereBound,
  PS <: HList
](table: Table,
  init: CQLQuery,
  usingPart: UsingPart = UsingPart.empty,
  wherePart: WherePart = WherePart.empty,
  private[phantom] val setPart: SetPart = SetPart.empty,
  casPart: CompareAndSetPart = CompareAndSetPart.empty,
  override val options: QueryOptions = QueryOptions.empty
) extends Query[Table, Record, Limit, Order, Status, Chain, PS](table, init, None.orNull, usingPart, options) with Batchable {

  override val qb: CQLQuery = usingPart merge setPart merge wherePart build init

  override protected[this] type QueryType[
    T <: CassandraTable[T, _],
    R,
    L <: LimitBound,
    O <: OrderBound,
    S <: ConsistencyBound,
    C <: WhereBound,
    P <: HList
  ] = UpdateQuery[T, R, L, O, S, C, P]

  def prepare()(implicit session: Session, keySpace: KeySpace, ev: PS =:!= HNil): PreparedBlock[PS] = {
    new PreparedBlock[PS](qb, options)
  }

  protected[this] def create[
    T <: CassandraTable[T, _],
    R,
    L <: LimitBound,
    O <: OrderBound,
    S <: ConsistencyBound,
    C <: WhereBound,
    P <: HList
  ](t: T, q: CQLQuery, r: Row => R, usingPart: UsingPart, options: QueryOptions): QueryType[T, R, L, O, S, C, P] = {
    new UpdateQuery[T, R, L, O, S, C, P](
      t,
      q,
      usingPart,
      wherePart,
      setPart,
      casPart,
      options
    )
  }

  override def ttl(seconds: Long): UpdateQuery[Table, Record, Limit, Order, Status, Chain, PS] = {
    new UpdateQuery(
      table,
      init, usingPart,
      wherePart,
      setPart append QueryBuilder.ttl(seconds.toString),
      casPart,
      options
    )
  }

  /**
    * The where method of a select query.
    * @param condition A where clause condition restricted by path dependant types.
    * @param ev An evidence request guaranteeing the user cannot chain multiple where clauses on the same query.
    * @return
    */
  override def where[
    RR,
    HL <: HList,
    Out <: HList
  ](condition: Table => QueryCondition[HL])(implicit
    ev: Chain =:= Unchainned,
    prepend: Prepend.Aux[HL, PS, Out]
  ): QueryType[Table, Record, Limit, Order, Status, Chainned, Out] = {
    new UpdateQuery(
      table,
      init,
      usingPart,
      wherePart append QueryBuilder.Update.where(condition(table).qb),
      setPart,
      casPart,
      options
    )
  }

  /**
    * The where method of a select query.
    * @param condition A where clause condition restricted by path dependant types.
    * @param ev An evidence request guaranteeing the user cannot chain multiple where clauses on the same query.
    * @return
    */
  override def and[
    RR,
    HL <: HList,
    Out <: HList
  ](condition: Table => QueryCondition[HL])(implicit
    ev: Chain =:= Chainned,
    prepend: Prepend.Aux[HL, PS, Out]
  ): QueryType[Table, Record, Limit, Order, Status, Chainned, Out] = {
    new UpdateQuery(
      table,
      init,
      usingPart,
      wherePart append QueryBuilder.Update.and(condition(table).qb),
      setPart,
      casPart,
      options
    )
  }

  final def modify[
    HL <: HList,
    Out <: HList
  ](clause: Table => UpdateClause.Condition[HL])(
    implicit prepend: Prepend.Aux[HL, HNil, Out]
  ): AssignmentsQuery[Table, Record, Limit, Order, Status, Chain, PS, Out] = {
    new AssignmentsQuery(
      table = table,
      init = init,
      usingPart = usingPart,
      wherePart = wherePart,
      setPart = setPart appendConditionally (clause(table).qb, !clause(table).skipped),
      casPart = casPart,
      options = options
    )
  }

  /**
   * Generates a conditional query clause based on CQL lightweight transactions.
   * Compare and set transactions only get executed if a particular condition is true.
   *
    * @param clause The Compare-And-Set clause to append to the builder.
   * @return A conditional query, now bound by a compare-and-set part.
   */
  def onlyIf(clause: Table => CompareAndSetClause.Condition): ConditionalQuery[Table, Record, Limit, Order, Status, Chain, PS, HNil] = {
    new ConditionalQuery(
      table,
      init,
      usingPart,
      wherePart,
      setPart,
      casPart append QueryBuilder.Update.onlyIf(clause(table).qb),
      options
    )
  }
}

sealed class AssignmentsQuery[
  Table <: CassandraTable[Table, _],
  Record,
  Limit <: LimitBound,
  Order <: OrderBound,
  Status <: ConsistencyBound,
  Chain <: WhereBound,
  PS <: HList,
  ModifyPrepared <: HList
](table: Table,
  val init: CQLQuery,
  usingPart: UsingPart = UsingPart.empty,
  wherePart : WherePart = WherePart.empty,
  private[phantom] val setPart : SetPart = SetPart.empty,
  casPart : CompareAndSetPart = CompareAndSetPart.empty,
  override val options: QueryOptions
) extends ExecutableStatement with Batchable {

  val qb: CQLQuery = usingPart merge setPart merge wherePart merge casPart build init

  final def and[
    HL <: HList,
    Out <: HList
  ](clause: Table => UpdateClause.Condition[HL])(
    implicit prepend: Prepend.Aux[HL, ModifyPrepared, Out]
  ): AssignmentsQuery[Table, Record, Limit, Order, Status, Chain, PS, Out] = {
    new AssignmentsQuery(
      table = table,
      init = init,
      usingPart = usingPart,
      wherePart = wherePart,
      setPart appendConditionally (clause(table).qb, !clause(table).skipped),
      casPart = casPart,
      options = options
    )
  }

  final def timestamp(value: Long): AssignmentsQuery[Table, Record, Limit, Order, Status, Chain, PS, ModifyPrepared] = {
    new AssignmentsQuery(
      table = table,
      init = init,
      usingPart = usingPart append QueryBuilder.timestamp(value),
      wherePart = wherePart,
      setPart = setPart,
      casPart = casPart,
      options = options
    )
  }

  final def timestamp(value: DateTime): AssignmentsQuery[Table, Record, Limit, Order, Status, Chain, PS, ModifyPrepared] = {
    new AssignmentsQuery(
      table = table,
      init = init,
      usingPart = usingPart append QueryBuilder.timestamp(value.getMillis),
      wherePart = wherePart,
      setPart = setPart,
      casPart = casPart,
      options = options
    )
  }

  final def ttl(mark: PrepareMark): AssignmentsQuery[Table, Record, Limit, Order, Status, Chain, Long :: PS, ModifyPrepared] = {
    new AssignmentsQuery(
      table = table,
      init = init,
      usingPart = usingPart append QueryBuilder.ttl(mark.qb.queryString),
      wherePart = wherePart,
      setPart = setPart,
      casPart = casPart,
      options = options
    )
  }

  final def ttl(seconds: Long): AssignmentsQuery[Table, Record, Limit, Order, Status, Chain, PS, ModifyPrepared] = {
    new AssignmentsQuery(
      table = table,
      init = init,
      usingPart = usingPart append QueryBuilder.ttl(seconds.toString),
      wherePart = wherePart,
      setPart = setPart,
      casPart = casPart,
      options = options
    )
  }

  final def ttl(duration: ScalaDuration): AssignmentsQuery[Table, Record, Limit, Order, Status, Chain, PS, ModifyPrepared] = {
    ttl(duration.toSeconds)
  }

  def prepare[
    Rev <: HList,
    Reversed <: HList,
    Out <: HList
  ]()(
    implicit session: Session,
    keySpace: KeySpace,
    ev: PS =:!= HNil,
    rev: Reverse.Aux[PS, Rev],
    rev2: Reverse.Aux[ModifyPrepared, Reversed],
    prepend: Prepend.Aux[Reversed, Rev, Out]
  ): PreparedBlock[Out] = {
    new PreparedBlock(qb, options)
  }

  /**
   * Generates a conditional query clause based on CQL lightweight transactions.
   * Compare and set transactions only get executed if a particular condition is true.
   *
    * @param clause The Compare-And-Set clause to append to the builder.
   * @return A conditional query, now bound by a compare-and-set part.
   */
  def onlyIf(clause: Table => CompareAndSetClause.Condition): ConditionalQuery[Table, Record, Limit, Order, Status, Chain, PS, ModifyPrepared] = {
    new ConditionalQuery(
      table,
      init,
      usingPart,
      wherePart,
      setPart,
      casPart append QueryBuilder.Update.onlyIf(clause(table).qb),
      options
    )
  }

  def ifExists: ConditionalQuery[Table, Record, Limit, Order, Status, Chain, PS, ModifyPrepared] = {
    new ConditionalQuery(
      table,
      init,
      usingPart,
      wherePart,
      setPart,
      casPart append QueryBuilder.Update.ifExists,
      options
    )
  }

  def consistencyLevel_=(level: ConsistencyLevel)(
    implicit ev: Status =:= Unspecified,
    session: Session
  ): AssignmentsQuery[Table, Record, Limit, Order, Specified, Chain, PS, ModifyPrepared] = {
    if (session.protocolConsistency) {
      new AssignmentsQuery(
        table,
        init,
        usingPart,
        wherePart,
        setPart,
        casPart,
        options.consistencyLevel_=(level)
      )
    } else {
      new AssignmentsQuery(
        table,
        init,
        usingPart append QueryBuilder.consistencyLevel(level.toString),
        wherePart,
        setPart,
        casPart,
        options
      )
    }

  }
}

sealed class ConditionalQuery[
  Table <: CassandraTable[Table, _],
  Record,
  Limit <: LimitBound,
  Order <: OrderBound,
  Status <: ConsistencyBound,
  Chain <: WhereBound,
  PS <: HList,
  ModifyPrepared <: HList
](table: Table,
  val init: CQLQuery,
  usingPart: UsingPart = UsingPart.empty,
  wherePart : WherePart = WherePart.empty,
  private[phantom] val setPart : SetPart = SetPart.empty,
  casPart : CompareAndSetPart = CompareAndSetPart.empty,
  override val options: QueryOptions
) extends ExecutableStatement with Batchable {

  override def qb: CQLQuery = {
    usingPart merge setPart merge wherePart merge casPart build init
  }

  final def and(
    clause: Table => CompareAndSetClause.Condition
  ): ConditionalQuery[Table, Record, Limit, Order, Status, Chain, PS, ModifyPrepared] = {
    new ConditionalQuery(
      table,
      init,
      usingPart,
      wherePart,
      setPart,
      casPart append QueryBuilder.Update.and(clause(table).qb),
      options
    )
  }

  def consistencyLevel_=(level: ConsistencyLevel)(
    implicit ev: Status =:= Unspecified, session: Session
  ): ConditionalQuery[Table, Record, Limit, Order, Specified, Chain, PS, ModifyPrepared] = {
    if (session.protocolConsistency) {
      new ConditionalQuery(
        table = table,
        init = init,
        usingPart = usingPart,
        wherePart = wherePart,
        setPart = setPart,
        casPart = casPart,
        options.consistencyLevel_=(level)
      )
    } else {
      new ConditionalQuery(
        table = table,
        init = init,
        usingPart = usingPart append QueryBuilder.consistencyLevel(level.toString),
        wherePart = wherePart,
        setPart = setPart,
        casPart = casPart,
        options = options
      )
    }
  }

  def ttl(seconds: Long): ConditionalQuery[Table, Record, Limit, Order, Status, Chain, PS, ModifyPrepared] = {
    new ConditionalQuery(
      table,
      init,
      usingPart,
      wherePart,
      setPart append QueryBuilder.ttl(seconds.toString),
      casPart,
      options
    )
  }

  final def ttl(duration: ScalaDuration): ConditionalQuery[Table, Record, Limit, Order, Status, Chain, PS, ModifyPrepared] = {
    ttl(duration.toSeconds)
  }

  def prepare[Rev <: HList, Rev2 <: HList, Out <: HList]()(
    implicit session: Session,
    keySpace: KeySpace,
    ev: PS =:!= HNil,
    rev: Reverse.Aux[PS, Rev],
    rev2: Reverse.Aux[ModifyPrepared, Rev2],
    prepend: Prepend.Aux[Rev2, Rev, Out]
  ): PreparedBlock[Out] = new PreparedBlock(qb, options)
}

object UpdateQuery {

  type Default[T <: CassandraTable[T, _], R] = UpdateQuery[T, R, Unlimited, Unordered, Unspecified, Unchainned, HNil]

  def apply[T <: CassandraTable[T, _], R](table: T)(implicit keySpace: KeySpace): UpdateQuery.Default[T, R] = {
    new UpdateQuery[T, R, Unlimited, Unordered, Unspecified, Unchainned, HNil](
      table,
      QueryBuilder.Update.update(
        QueryBuilder.keyspace(keySpace.name, table.tableName).queryString
      )
    )
  }

}
