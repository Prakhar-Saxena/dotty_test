package io.getquill.context.mirror

import io.getquill.dsl._
import scala.reflect.ClassTag

trait MirrorDecoders extends EncodingDsl {

  override type PrepareRow = Row
  override type ResultRow = Row

  case class MirrorDecoder[T](decoder: Decoder[T]) extends Decoder[T] {
    override def apply(index: Int, row: ResultRow) =
      decoder(index, row)
  }

  def decoder[T: ClassTag]: Decoder[T] = MirrorDecoder((index: Int, row: ResultRow) => row.apply[T](index))

  def decoderUnsafe[T]: Decoder[T] = MirrorDecoder((index: Int, row: ResultRow) => row.data(index).asInstanceOf[T])

  //implicit def mappedDecoder[I, O](implicit mapped: MappedEncoding[I, O], d: Decoder[I]): Decoder[O] =
  //  MirrorDecoder((index: Index, row: ResultRow) => mapped.f(d.apply(index, row)))

  implicit def optionDecoder[T](implicit d: Decoder[T]): Decoder[Option[T]] =
    MirrorDecoder((index: Int, row: ResultRow) =>
      row[Option[Any]](index) match {
        case Some(v) => Some(d(0, Row.fromList(v)))
        case None    => None
      })

  implicit val stringDecoder: Decoder[String] = decoder[String]
  implicit val bigDecimalDecoder: Decoder[BigDecimal] = decoder[BigDecimal]
  implicit val booleanDecoder: Decoder[Boolean] = decoder[Boolean]
  implicit val byteDecoder: Decoder[Byte] = decoder[Byte]
  implicit val shortDecoder: Decoder[Short] = decoder[Short]
  implicit val intDecoder: Decoder[Int] = decoder[Int]
  implicit val longDecoder: Decoder[Long] = decoder[Long]
  implicit val floatDecoder: Decoder[Float] = decoder[Float]
  implicit val doubleDecoder: Decoder[Double] = decoder[Double]
  implicit val byteArrayDecoder: Decoder[Array[Byte]] = decoder[Array[Byte]]
  // implicit val dateDecoder: Decoder[Date] = decoder[Date]
  // implicit val localDateDecoder: Decoder[LocalDate] = decoder[LocalDate]
  // implicit val uuidDecoder: Decoder[UUID] = decoder[UUID]
}