/*
 * Copyright 2019 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package magnolify.avro

import java.nio.ByteBuffer
import java.time.format.{DateTimeFormatter, DateTimeFormatterBuilder}
import java.time._
import java.util.UUID
import java.{util => ju}

import magnolia._
import magnolify.shared.{CaseMapper, Converter}
import magnolify.shims.FactoryCompat
import magnolify.shims.JavaConverters._
import org.apache.avro.generic.{GenericArray, GenericData, GenericRecord, GenericRecordBuilder}
import org.apache.avro.{JsonProperties, LogicalTypes, Schema}

import scala.annotation.{implicitNotFound, StaticAnnotation}
import scala.collection.concurrent
import scala.language.experimental.macros
import scala.language.implicitConversions

class doc(doc: String) extends StaticAnnotation with Serializable {
  override def toString: String = doc
}

sealed trait AvroType[T] extends Converter[T, GenericRecord, GenericRecord] {
  val schema: Schema
  def apply(r: GenericRecord): T = from(r)
  def apply(t: T): GenericRecord = to(t)
}

object AvroType {
  implicit def apply[T: AvroField.Record]: AvroType[T] = AvroType(CaseMapper.identity)

  def apply[T](cm: CaseMapper)(implicit f: AvroField.Record[T]): AvroType[T] = {
    f.schema(cm) // fail fast on bad annotations
    new AvroType[T] {
      private val caseMapper: CaseMapper = cm
      @transient override lazy val schema: Schema = f.schema(caseMapper)
      override def from(v: GenericRecord): T = f.from(v)(caseMapper)
      override def to(v: T): GenericRecord = f.to(v)(caseMapper)
    }
  }
}

sealed trait AvroField[T] extends Serializable {
  type FromT
  type ToT

  @transient private lazy val schemaCache: concurrent.Map[ju.UUID, Schema] =
    concurrent.TrieMap.empty
  protected def schemaString(cm: CaseMapper): String
  def schema(cm: CaseMapper): Schema =
    schemaCache.getOrElseUpdate(cm.uuid, new Schema.Parser().parse(schemaString(cm)))
  def defaultVal: Any
  def from(v: FromT)(cm: CaseMapper): T
  def to(v: T)(cm: CaseMapper): ToT

  def fromAny(v: Any)(cm: CaseMapper): T = from(v.asInstanceOf[FromT])(cm)
}

object AvroField {
  sealed trait Aux[T, From, To] extends AvroField[T] {
    override type FromT = From
    override type ToT = To
  }

  sealed trait Record[T] extends Aux[T, GenericRecord, GenericRecord]

  //////////////////////////////////////////////////

  type Typeclass[T] = AvroField[T]

  def combine[T](caseClass: CaseClass[Typeclass, T]): Record[T] = new Record[T] {
    override protected def schemaString(cm: CaseMapper): String = Schema
      .createRecord(
        caseClass.typeName.short,
        getDoc(caseClass.annotations, caseClass.typeName.full),
        caseClass.typeName.owner,
        false,
        caseClass.parameters.map { p =>
          new Schema.Field(
            cm.map(p.label),
            p.typeclass.schema(cm),
            getDoc(p.annotations, s"${caseClass.typeName.full}#${p.label}"),
            p.typeclass.defaultVal
          )
        }.asJava
      )
      .toString

    override def defaultVal: Any = null

    override def from(v: GenericRecord)(cm: CaseMapper): T =
      caseClass.construct(p => p.typeclass.fromAny(v.get(cm.map(p.label)))(cm))

    override def to(v: T)(cm: CaseMapper): GenericRecord =
      caseClass.parameters
        .foldLeft(new GenericRecordBuilder(schema(cm))) { (b, p) =>
          val f = p.typeclass.to(p.dereference(v))(cm)
          if (f == null) b else b.set(cm.map(p.label), f)
        }
        .build()

    private def getDoc(annotations: Seq[Any], name: String): String = {
      val docs = annotations.collect { case d: doc => d.toString }
      require(docs.size <= 1, s"More than one @doc annotation: $name")
      docs.headOption.orNull
    }
  }

  @implicitNotFound("Cannot derive AvroField for sealed trait")
  private sealed trait Dispatchable[T]
  def dispatch[T: Dispatchable](sealedTrait: SealedTrait[Typeclass, T]): Record[T] = ???

  implicit def gen[T]: Record[T] = macro Magnolia.gen[T]

  //////////////////////////////////////////////////

  def apply[T](implicit f: AvroField[T]): AvroField[T] = f

  def from[T]: FromWord[T] = new FromWord[T]

  class FromWord[T] {
    def apply[U](f: T => U)(g: U => T)(implicit af: AvroField[T]): AvroField[U] =
      new AvroField[U] {
        override type FromT = af.FromT
        override type ToT = af.ToT
        override protected def schemaString(cm: CaseMapper): String = af.schema(cm).toString
        override def defaultVal: Any = af.defaultVal
        override def from(v: FromT)(cm: CaseMapper): U = f(af.from(v)(cm))
        override def to(v: U)(cm: CaseMapper): ToT = af.to(g(v))(cm)
      }
  }

  //////////////////////////////////////////////////

  private def aux[T, From, To](tpe: Schema.Type)(f: From => T)(g: T => To): AvroField[T] =
    new AvroField[T] {
      override type FromT = From
      override type ToT = To
      override protected def schemaString(cm: CaseMapper): String = Schema.create(tpe).toString
      override def defaultVal: Any = null
      override def from(v: FromT)(cm: CaseMapper): T = f(v)
      override def to(v: T)(cm: CaseMapper): ToT = g(v)
    }

  private def aux2[T, Repr](tpe: Schema.Type)(f: Repr => T)(g: T => Repr): AvroField[T] =
    aux[T, Repr, Repr](tpe)(f)(g)

  private def id[T](tpe: Schema.Type): AvroField[T] = aux[T, T, T](tpe)(identity)(identity)

  implicit val afBoolean = id[Boolean](Schema.Type.BOOLEAN)
  implicit val afInt = id[Int](Schema.Type.INT)
  implicit val afLong = id[Long](Schema.Type.LONG)
  implicit val afFloat = id[Float](Schema.Type.FLOAT)
  implicit val afDouble = id[Double](Schema.Type.DOUBLE)
  implicit val afBytes = aux2[Array[Byte], ByteBuffer](Schema.Type.BYTES)(bb =>
    ju.Arrays.copyOfRange(bb.array(), bb.position(), bb.limit())
  )(ByteBuffer.wrap)
  implicit val afString =
    aux[String, CharSequence, String](Schema.Type.STRING)(_.toString)(identity)
  implicit val afUnit =
    aux2[Unit, JsonProperties.Null](Schema.Type.NULL)(_ => ())(_ => JsonProperties.NULL_VALUE)

  implicit def afOption[T](implicit f: AvroField[T]): AvroField[Option[T]] =
    new Aux[Option[T], f.FromT, f.ToT] {
      override protected def schemaString(cm: CaseMapper): String =
        Schema.createUnion(Schema.create(Schema.Type.NULL), f.schema(cm)).toString
      override def defaultVal: Any = JsonProperties.NULL_VALUE
      override def from(v: f.FromT)(cm: CaseMapper): Option[T] =
        if (v == null) None else Some(f.from(v)(cm))
      override def to(v: Option[T])(cm: CaseMapper): f.ToT = v match {
        case None    => null.asInstanceOf[f.ToT]
        case Some(x) => f.to(x)(cm)
      }
    }

  implicit def afIterable[T, C[_]](implicit
    f: AvroField[T],
    ti: C[T] => Iterable[T],
    fc: FactoryCompat[T, C[T]]
  ): AvroField[C[T]] =
    new Aux[C[T], ju.List[f.FromT], GenericArray[f.ToT]] {
      override protected def schemaString(cm: CaseMapper): String =
        Schema.createArray(f.schema(cm)).toString
      override def defaultVal: Any = ju.Collections.emptyList()
      override def from(v: ju.List[f.FromT])(cm: CaseMapper): C[T] =
        if (v == null) fc.newBuilder.result()
        else fc.build(v.asScala.iterator.map(p => f.from(p)(cm)))
      override def to(v: C[T])(cm: CaseMapper): GenericArray[f.ToT] =
        if (v.isEmpty) {
          null
        } else {
          new GenericData.Array[f.ToT](schema(cm), v.iterator.map(f.to(_)(cm)).toList.asJava)
        }
    }

  implicit def afMap[T](implicit f: AvroField[T]): AvroField[Map[String, T]] =
    new Aux[Map[String, T], ju.Map[CharSequence, f.FromT], ju.Map[String, f.ToT]] {
      override protected def schemaString(cm: CaseMapper): String =
        Schema.createMap(f.schema(cm)).toString
      override def defaultVal: Any = ju.Collections.emptyMap()
      override def from(v: ju.Map[CharSequence, f.FromT])(cm: CaseMapper): Map[String, T] =
        if (v == null) {
          Map.empty
        } else {
          v.asScala.iterator.map(kv => (kv._1.toString, f.from(kv._2)(cm))).toMap
        }
      override def to(v: Map[String, T])(cm: CaseMapper): ju.Map[String, f.ToT] =
        if (v.isEmpty) null else v.iterator.map(kv => (kv._1, f.to(kv._2)(cm))).toMap.asJava
    }

  //////////////////////////////////////////////////

  def logicalType[T](lt: LogicalType): LogicalTypeWord[T] = new LogicalTypeWord[T](lt)

  class LogicalTypeWord[T](val lt: LogicalType) extends Serializable {
    def apply[U](f: T => U)(g: U => T)(implicit af: AvroField[T]): AvroField[U] = {
      new AvroField[U] {
        override type FromT = af.FromT
        override type ToT = af.ToT
        override protected def schemaString(cm: CaseMapper): String = {
          // `LogicalType#addToSchema` mutates `Schema`, make a copy first
          val schema = new Schema.Parser().parse(af.schema(cm).toString)
          lt.get.addToSchema(schema)
          schema.toString
        }
        override def defaultVal: Any = af.defaultVal
        override def from(v: FromT)(cm: CaseMapper): U = f(af.from(v)(cm))
        override def to(v: U)(cm: CaseMapper): ToT = af.to(g(v))(cm)
      }
    }
  }

  // https://avro.apache.org/docs/1.8.2/spec.html#Logical+Types
  // Precision and scale are not encoded in the `BigDecimal` type and must be specified
  def bigDecimal(precision: Int, scale: Int = 0): AvroField[BigDecimal] =
    logicalType[Array[Byte]](LogicalType.Decimal(precision, scale))(ba =>
      BigDecimal(BigInt(ba), scale)
    ) { bd =>
      val scaled = bd.setScale(scale)
      require(
        scaled.precision <= precision,
        s"Cannot encode BigDecimal $bd: precision ${scaled.precision} > $precision" +
          (if (bd.scale == scaled.scale) {
             ""
           } else {
             s" after set scale from ${bd.scale} to ${scaled.scale}"
           })
      )
      scaled.bigDecimal.unscaledValue().toByteArray
    }

  implicit val afUuid: AvroField[UUID] =
    logicalType[String](LogicalType.UUID)(UUID.fromString)(_.toString)
  implicit val afDate: AvroField[LocalDate] =
    logicalType[Int](LogicalType.Date)(LocalDate.ofEpochDay(_))(_.toEpochDay.toInt)
}

sealed class LogicalType(avro: => org.apache.avro.LogicalType) extends Serializable {
  def this(name: String) = this(new org.apache.avro.LogicalType(name))
  def get: org.apache.avro.LogicalType = avro
}

object LogicalType {
  def apply(name: String): LogicalType = new LogicalType(name)

  case class Decimal(precision: Int, scale: Int = 0)
      extends LogicalType(LogicalTypes.decimal(precision, scale))
  case object UUID extends LogicalType(LogicalTypes.uuid())

  // Map to `Instant`
  case object TimestampMicros extends LogicalType(LogicalTypes.timestampMicros())
  case object TimestampMillis extends LogicalType(LogicalTypes.timestampMillis())

  // Map to `LocalDate`
  case object Date extends LogicalType(LogicalTypes.date())

  // Map to `LocalTime`
  case object TimeMicros extends LogicalType(LogicalTypes.timeMicros())
  case object TimeMillis extends LogicalType(LogicalTypes.timeMillis())

  // Map to `LocalDateTime`
  // `LogicalTypes.localTimestampMicros` and `LogicalTypes.localTimestampMillis` are Avro 1.10.0+.
  case object LocalTimestampMicros extends LogicalType("local-timestamp-micros")
  case object LocalTimestampMillis extends LogicalType("local-timestamp-millis")

  object Micros {
    implicit val afTimestampMicros: AvroField[Instant] =
      AvroField.logicalType[Long](LogicalType.TimestampMicros)(us =>
        Instant.ofEpochMilli(us / 1000)
      )(_.toEpochMilli * 1000)

    implicit val afTimeMicros: AvroField[LocalTime] =
      AvroField.logicalType[Long](LogicalType.TimeMicros)(us => LocalTime.ofNanoOfDay(us * 1000))(
        _.toNanoOfDay / 1000
      )

    implicit val afLocalTimestampMicros: AvroField[LocalDateTime] =
      AvroField.logicalType[Long](LogicalType.LocalTimestampMicros)(us =>
        LocalDateTime.ofInstant(Instant.ofEpochMilli(us / 1000), ZoneOffset.UTC)
      )(_.toInstant(ZoneOffset.UTC).toEpochMilli * 1000)
  }

  object Millis {
    implicit val afTimestampMillis: AvroField[Instant] =
      AvroField.logicalType[Long](LogicalType.TimestampMillis)(Instant.ofEpochMilli)(_.toEpochMilli)

    implicit val afTimeMillis: AvroField[LocalTime] =
      AvroField.logicalType[Int](LogicalType.TimeMillis)(ms =>
        LocalTime.ofNanoOfDay(ms * 1000000L)
      )(t => (t.toNanoOfDay / 1000000).toInt)

    implicit val afLocalTimestampMillis: AvroField[LocalDateTime] =
      AvroField.logicalType[Long](LogicalType.LocalTimestampMillis)(ms =>
        LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC)
      )(_.toInstant(ZoneOffset.UTC).toEpochMilli)
  }

  object BigQuery {
    // NUMERIC
    // https://cloud.google.com/bigquery/docs/reference/standard-sql/data-types#numeric-type
    implicit val afBigQueryNumeric: AvroField[BigDecimal] = AvroField.bigDecimal(38, 9)

    // TIMESTAMP
    implicit val afBigQueryTimestamp: AvroField[Instant] = Micros.afTimestampMicros

    // DATE: `AvroField.afDate`

    // TIME
    implicit val afBigQueryTime: AvroField[LocalTime] = Micros.afTimeMicros

    // DATETIME -> sqlType: DATETIME
    implicit val afBigQueryDatetime: AvroField[LocalDateTime] =
      AvroField.logicalType[String](LogicalType("datetime"))(s =>
        LocalDateTime.from(datetimeParser.parse(s))
      )(datetimePrinter.format)

    // DATETIME
    // YYYY-[M]M-[D]D[ [H]H:[M]M:[S]S[.DDDDDD]]
    private val datetimePrinter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")
    private val datetimeParser = new DateTimeFormatterBuilder()
      .append(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
      .appendOptional(
        new DateTimeFormatterBuilder()
          .append(DateTimeFormatter.ofPattern(" HH:mm:ss"))
          .appendOptional(DateTimeFormatter.ofPattern(".SSSSSS"))
          .toFormatter
      )
      .toFormatter
      .withZone(ZoneOffset.UTC)
  }
}
