package schemaz
import scala.annotation.implicitNotFound

import recursion._
import scalaz.{ -\/, \/, \/-, ~> }

trait Realisation {
  type Prim[A]
  type SumTermId
  type ProductTermId
}

object Representation {
  type RSum[RA, A, RB, B]
  type RProd[RA, A, RB, B]
  type RSelf[A]
  type RSeq[R, A]
  type -*>[K, V]
  type -+>[K, V]
  type RRecord[RA, A]
  type RUnion[RA, A]
}

trait Distributes[P[_, _], Q[_, _]] {
  def dist[A0, A1, B0, B1](pa: P[A0, A1], pb: P[B0, B1]): P[Q[A0, B0], Q[A1, B1]]
}

final case class NIso[A, B](f: A => B, g: B => A) {

  def compose[C](other: NIso[B, C]): NIso[A, C] = NIso(other.f.compose(f), g.compose(other.g))
}

object NIso {

  def id[A] = NIso[A, A](identity, identity)

  implicit val nisoDistributesOverProduct: Distributes[NIso, Tuple2] =
    new Distributes[NIso, Tuple2] {

      def dist[A0, A1, B0, B1](pa: NIso[A0, A1], pb: NIso[B0, B1]): NIso[(A0, B0), (A1, B1)] =
        NIso(p0 => (pa.f(p0._1), pb.f(p0._2)), p1 => (pa.g(p1._1), pb.g(p1._2)))
    }

  implicit val nisoDistributesOverSum: Distributes[NIso, \/] = new Distributes[NIso, \/] {

    def dist[A0, A1, B0, B1](pa: NIso[A0, A1], pb: NIso[B0, B1]): NIso[A0 \/ B0, A1 \/ B1] =
      NIso(e0 => e0.bimap(pa.f, pb.f), e1 => e1.bimap(pa.g, pb.g))
  }

}

import Representation._

sealed trait SchemaF[Prim[_], SumTermId, ProductTermId, F[_], A] {
  def hmap[G[_]](nt: F ~> G): SchemaF[Prim, SumTermId, ProductTermId, G, A]
}

////////////////////
// The Schema ADT
////////////////////

// "Essential" nodes. In theory every possible type can be represented using only `One`, `:+:` and `:*:`

final case class One[F[_], Prim[_], SumTermId, ProductTermId]()
    extends SchemaF[Prim, SumTermId, ProductTermId, F, Unit] {
  def hmap[G[_]](nt: F ~> G): SchemaF[Prim, SumTermId, ProductTermId, G, Unit] = One()
}

/**
 * The sum of two schemas, yielding the schema for `A \/ B`
 */
final case class SumF[F[_], A, B, Prim[_], SumTermId, ProductTermId](
  left: F[A],
  right: F[B]
) extends SchemaF[Prim, SumTermId, ProductTermId, F, A \/ B] {

  def hmap[G[_]](
    nt: F ~> G
  ): SchemaF[Prim, SumTermId, ProductTermId, G, A \/ B] =
    SumF(
      nt(left),
      nt(right)
    )
  override def toString: String = s"$left :+: $right"
}

/**
 * The product of two schemas, yielding the schema for `(A, B)`
 */
final case class ProdF[F[_], A, B, Prim[_], SumTermId, ProductTermId](
  left: F[A],
  right: F[B]
) extends SchemaF[Prim, SumTermId, ProductTermId, F, (A, B)] {

  def hmap[G[_]](
    nt: F ~> G
  ): SchemaF[Prim, SumTermId, ProductTermId, G, (A, B)] =
    ProdF(nt(left), nt(right))
  override def toString: String = s"$left :*: $right"
}

// "Extra" nodes, making it more convenient to represent real-world types

/**
 * The schema of a primitive type in the context of this `SchemaModule`
 */
final case class PrimSchemaF[F[_], A, Prim[_], SumTermId, ProductTermId](prim: Prim[A])
    extends SchemaF[Prim, SumTermId, ProductTermId, F, A] {

  def hmap[G[_]](nt: F ~> G): SchemaF[Prim, SumTermId, ProductTermId, G, A] =
    PrimSchemaF[G, A, Prim, SumTermId, ProductTermId](prim)
}

/**
 * A named branch of an union
 */
final case class BranchF[F[_], A, Prim[_], SumTermId, ProductTermId](
  id: SumTermId,
  schema: F[A]
) extends SchemaF[Prim, SumTermId, ProductTermId, F, A] {

  def hmap[G[_]](nt: F ~> G): SchemaF[Prim, SumTermId, ProductTermId, G, A] =
    BranchF(id, nt(schema))
}

/**
 * An union, eg. a sum of named branches
 * This class cannot be constructed directly, you must use the `SchemaModule#union` method.
 */
final case class UnionF[F[_], A, Prim[_], SumTermId, ProductTermId](
  choices: F[A]
) extends SchemaF[Prim, SumTermId, ProductTermId, F, A] {

  def hmap[G[_]](nt: F ~> G): SchemaF[Prim, SumTermId, ProductTermId, G, A] =
    UnionF[G, A, Prim, SumTermId, ProductTermId](nt(choices))
}

/**
 * A named field of a record
 */
final case class FieldF[F[_], A, Prim[_], SumTermId, ProductTermId](
  id: ProductTermId,
  schema: F[A]
) extends SchemaF[Prim, SumTermId, ProductTermId, F, A] {

  def hmap[G[_]](nt: F ~> G): SchemaF[Prim, SumTermId, ProductTermId, G, A] =
    FieldF(id, nt(schema))
}

/**
 * A record, eg. a product of named fields
 * This class cannot be constructed directly, you must use the `SchemaModule#record` method.
 */
final case class RecordF[F[_], A, Prim[_], SumTermId, ProductTermId](
  fields: F[A]
) extends SchemaF[Prim, SumTermId, ProductTermId, F, A] {

  def hmap[G[_]](
    nt: F ~> G
  ): SchemaF[Prim, SumTermId, ProductTermId, G, A] =
    RecordF[G, A, Prim, SumTermId, ProductTermId](nt(fields))
}

/**
 * A sequence
 */
final case class SeqF[F[_], A, Prim[_], SumTermId, ProductTermId](element: F[A])
    extends SchemaF[Prim, SumTermId, ProductTermId, F, List[A]] {

  def hmap[G[_]](nt: F ~> G): SchemaF[Prim, SumTermId, ProductTermId, G, List[A]] =
    SeqF(nt(element))
}

final case class SelfReference[F[_], H[_], A, Prim[_], SumTermId, ProductTermId](
  private val ref: () => F[A],
  private val nattrans: F ~> H
) extends SchemaF[Prim, SumTermId, ProductTermId, H, A] {

  lazy val unroll: H[A] = nattrans(ref())

  def hmap[G[_]](nt: H ~> G): SchemaF[Prim, SumTermId, ProductTermId, G, A] =
    SelfReference[F, G, A, Prim, SumTermId, ProductTermId](ref, nt.compose(nattrans))
}

trait Transform[F[_]] {
  def apply[A, B](fa: F[A], niso: NIso[A, B]): F[B]
}

/**
 * An interpreter able to derive a `F[A]` from a schema for `A` (for any `A`).
 * Such interpreters will usually be implemented using a recursion scheme like
 * 'cataNT`or hyloNT`.
 */
trait Interpreter[F[_], G[_]] { self =>

  /**
   * A natural transformation that will transform a schema for any type `A`
   * into an `F[A]`.
   */
  def interpret: F ~> G

  def compose[H[_]](nt: H ~> F) = self match {
    case i: ComposedInterpreter[h, G, F] => ComposedInterpreter(i.underlying, i.nt.compose(nt))
    case x                               => ComposedInterpreter(x, nt)
  }
}

final case class ComposedInterpreter[F[_], G[_], H[_]](
  underlying: Interpreter[F, G],
  nt: H ~> F
) extends Interpreter[H, G] {
  final override val interpret = underlying.interpret.compose(nt)
}

class CataInterpreter[S[_[_], _], F[_]](
  algebra: HAlgebra[S, F]
)(implicit ev: HFunctor[S])
    extends Interpreter[Fix[S, ?], F] {
  final override val interpret = cataNT(algebra)
}

class HyloInterpreter[S[_[_], _], F[_], G[_]](
  coalgebra: HCoalgebra[S, G],
  algebra: HAlgebra[S, F]
)(implicit ev: HFunctor[S])
    extends Interpreter[G, F] {
  final override val interpret = hyloNT(coalgebra, algebra)
}

@implicitNotFound(
  msg = "It seems like the following representation type isn't isomorphic to a product of named fields: ${A}"
)
trait IsRecord[A]

object IsRecord {
  implicit def singleFieldIsRecord[K, V]: IsRecord[K -*> V] = new IsRecord[K -*> V] {}

  implicit def productIsRecord[L: IsRecord, R: IsRecord, X, Y]: IsRecord[RProd[L, X, R, Y]] =
    new IsRecord[RProd[L, X, R, Y]] {}

  /* implicit def isoIsRecord[R: IsRecord, A0, A]: IsRecord[RIso[R, A0, A]] =
    new IsRecord[RIso[R, A0, A]] {}
 */
}

@implicitNotFound(
  msg = "It seems like the following representation type isn't isomorphic to a sum of named branches: ${A}"
)
trait IsUnion[A]

object IsUnion {

  implicit def singleBranchIsUnion[K, V]: IsUnion[K -+> V] = new IsUnion[K -+> V] {}

  implicit def sumIsUnion[L: IsUnion, R: IsUnion, X, Y]: IsUnion[RSum[L, X, R, Y]] =
    new IsUnion[RSum[L, X, R, Y]] {}
}

object SchemaF {

  implicit def schemaHFunctor[Prim[_], SumTermId, ProductTermId] =
    new HFunctor[SchemaF[Prim, SumTermId, ProductTermId, ?[_], ?]] {

      def hmap[F[_], G[_]](nt: F ~> G) =
        new (SchemaF[Prim, SumTermId, ProductTermId, F, ?] ~> SchemaF[
          Prim,
          SumTermId,
          ProductTermId,
          G,
          ?
        ]) {
          def apply[A](fa: SchemaF[Prim, SumTermId, ProductTermId, F, A]) = fa.hmap(nt)
        }
    }

}

trait Tagged[Repr]
final class Tag[Repr] {
  def apply[A](a: A): A with Tagged[Repr] = a.asInstanceOf[A with Tagged[Repr]]
}

object Tag {
  def apply[Repr] = new Tag[Repr]
}

trait SchemaModule[R <: Realisation] {

  val R: R

  type RInterpreter[F[_]] = Interpreter[Schema, F]

  type RSchema[F[_], A] = SchemaF[R.Prim, R.SumTermId, R.ProductTermId, F, A]

  type Schema[A] =
    Fix[SchemaF[R.Prim, R.SumTermId, R.ProductTermId, ?[_], ?], A]

  trait SchemaZ[T] {
    type Repr
    type A

    def p: NIso[A, T]
    def schema: Schema[A] with Tagged[Repr]

    def :*: [R2, B, U](
      left: SchemaZ.Aux[R2, B, U]
    )(implicit P: Distributes[NIso, Tuple2]): SchemaZ.Aux[RProd[R2, B, Repr, A], (B, A), (U, T)] =
      SchemaZ(
        P.dist(left.p, p),
        Tag[RProd[R2, B, Repr, A]].apply[Schema[(B, A)]](Fix(new ProdF(left.schema, schema)))
      )

    def :+: [R2, B, U](
      left: SchemaZ.Aux[R2, B, U]
    )(implicit P: Distributes[NIso, \/]): SchemaZ.Aux[RSum[R2, B, Repr, A], B \/ A, U \/ T] =
      SchemaZ(
        P.dist(left.p, p),
        Tag[RSum[R2, B, Repr, A]].apply[Schema[B \/ A]](Fix(new SumF(left.schema, schema)))
      )

    def to[F[_]](implicit interpreter: RInterpreter[F], trans: Transform[F]): F[T] =
      trans(interpreter.interpret(schema), p)

  }

  object SchemaZ {

    type Aux[R0, A0, T] = SchemaZ[T] {
      type Repr = R0; type A = A0
    }

    def apply[R0, A0, T](
      p: NIso[A0, T],
      schema: Schema[A0] with Tagged[R0]
    ): SchemaZ.Aux[R0, A0, T] = SchemaZ4[R0, A0, T](p, schema)
  }

  sealed case class SchemaZ4[R0, A0, T](p: NIso[A0, T], schema: Schema[A0] with Tagged[R0])
      extends SchemaZ[T] {

    type Repr = R0
    type A    = A0

  }

  implicit class ProductTermIdOps[I <: R.ProductTermId](id: I) {

    def -*> [T](schema: SchemaZ[T]): SchemaZ.Aux[I -*> schema.Repr, schema.A, T] =
      SchemaZ(
        schema.p,
        Tag[I -*> schema.Repr]
          .apply[Schema[schema.A]](Fix(FieldF(id.asInstanceOf[R.ProductTermId], schema.schema)))
      )
  }

  implicit class SumTermIdOps[I <: R.SumTermId](id: I) {

    def -+> [T](schema: SchemaZ[T]): SchemaZ.Aux[I -+> schema.Repr, schema.A, T] =
      SchemaZ(
        schema.p,
        Tag[I -+> schema.Repr]
          .apply[Schema[schema.A]](Fix(BranchF(id.asInstanceOf[R.SumTermId], schema.schema)))
      )
  }

  type ROne[F[_]]        = One[F, R.Prim, R.SumTermId, R.ProductTermId]
  type RPrim[F[_], A]    = PrimSchemaF[F, A, R.Prim, R.SumTermId, R.ProductTermId]
  type Sum[F[_], A, B]   = SumF[F, A, B, R.Prim, R.SumTermId, R.ProductTermId]
  type Prod[F[_], A, B]  = ProdF[F, A, B, R.Prim, R.SumTermId, R.ProductTermId]
  type Branch[F[_], A]   = BranchF[F, A, R.Prim, R.SumTermId, R.ProductTermId]
  type Union[F[_], A]    = UnionF[F, A, R.Prim, R.SumTermId, R.ProductTermId]
  type Field[F[_], A]    = FieldF[F, A, R.Prim, R.SumTermId, R.ProductTermId]
  type Record[F[_], A]   = RecordF[F, A, R.Prim, R.SumTermId, R.ProductTermId]
  type Sequence[F[_], A] = SeqF[F, A, R.Prim, R.SumTermId, R.ProductTermId]
  type Self[F[_], A]     = SelfReference[Any, F, A, R.Prim, R.SumTermId, R.ProductTermId]

  object Interpreter {

    def cata[S[_[_], _], F[_]](alg: HAlgebra[S, F])(implicit ev: HFunctor[S]) =
      new CataInterpreter[S, F](alg)

    def hylo[S[_[_], _], F[_], G[_]](coalg: HCoalgebra[S, G], alg: HAlgebra[S, F])(
      implicit ev: HFunctor[S]
    ) = new HyloInterpreter(coalg, alg)

  }

  ////////////////
  // Public API
  ////////////////

  final def unit: SchemaZ.Aux[Unit, Unit, Unit] =
    SchemaZ(
      NIso.id,
      Tag[Unit].apply[Schema[Unit]](
        Fix(
          One()
        )
      )
    )

  final def prim[A](prim: R.Prim[A]): SchemaZ.Aux[A, A, A] =
    SchemaZ(
      NIso.id,
      Tag[A].apply[Schema[A]](
        Fix(
          PrimSchemaF(prim)
        )
      )
    )

  final def union[Repr: IsUnion, A, T](
    choices: SchemaZ.Aux[Repr, A, T]
  ): SchemaZ.Aux[RUnion[Repr, A], A, T] =
    SchemaZ(choices.p, Tag[RUnion[Repr, A]].apply[Schema[A]](Fix(UnionF(choices.schema))))

  final def sealedTrait[Repr: IsUnion, Branches, A, T](
    branches: SchemaZ.Aux[Repr, A, Branches],
    isoA: NIso[Branches, T]
  ): SchemaZ.Aux[RUnion[Repr, Branches], A, T] =
    SchemaZ(
      branches.p.compose(isoA),
      Tag[RUnion[Repr, Branches]].apply[Schema[A]](union(branches).schema)
    )

  final def optional[T](
    aSchema: SchemaZ[T]
  ): SchemaZ.Aux[RSum[aSchema.Repr, aSchema.A, Unit, Unit], aSchema.A \/ Unit, Option[T]] =
    SchemaZ(
      NIso[aSchema.A \/ Unit, Option[T]](
        _.swap.toOption.map(aSchema.p.f),
        _.fold[aSchema.A \/ Unit](\/-(()))(a0 => -\/(aSchema.p.g(a0)))
      ),
      Tag[RSum[aSchema.Repr, aSchema.A, Unit, Unit]]
        .apply[Schema[aSchema.A \/ Unit]](Fix(SumF(aSchema.schema, unit.schema)))
    )

  final def record[Repr: IsRecord, A, T](
    terms: SchemaZ.Aux[Repr, A, T]
  ): SchemaZ.Aux[RRecord[Repr, A], A, T] =
    SchemaZ(terms.p, Tag[RRecord[Repr, A]].apply[Schema[A]](Fix(RecordF(terms.schema))))

  final def caseClass[Repr: IsRecord, Fields, A, T](
    fields: SchemaZ.Aux[Repr, A, Fields],
    isoA: NIso[Fields, T]
  ): SchemaZ.Aux[RRecord[Repr, A], A, T] =
    SchemaZ(fields.p.compose(isoA), record(fields).schema)

  final def seq[Repr, A, T](
    element: SchemaZ.Aux[Repr, A, T]
  ): SchemaZ.Aux[RSeq[Repr, A], List[A], List[T]] =
    SchemaZ(
      NIso[List[A], List[T]](_.map(element.p.f), _.map(element.p.g)),
      Tag[RSeq[Repr, A]].apply[Schema[List[A]]](Fix(SeqF(element.schema)))
    )

  final def iso[A0, A](
    base: SchemaZ[A0],
    iso: NIso[A0, A]
  ): SchemaZ.Aux[base.Repr, base.A, A] =
    SchemaZ(base.p.compose(iso), base.schema)

  final def self[A](root: => Schema[A]): SchemaZ.Aux[RSelf[A], A, A] =
    SchemaZ(
      NIso.id,
      Tag[RSelf[A]].apply[Schema[A]](
        Fix(
          SelfReference(() => root, new (Schema ~> Schema) {
            def apply[X](a: Schema[X]) = a
          })
        )
      )
    )

}
