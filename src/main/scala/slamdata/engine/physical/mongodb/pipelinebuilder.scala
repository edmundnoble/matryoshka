package slamdata.engine.physical.mongodb

import scalaz._
import Scalaz._

import slamdata.engine.{RenderTree, Terminal, NonTerminal, Error}
import slamdata.engine.fp._

case class ExprPointer(schema: PipelineSchema, ref: ExprOp.DocVar)

sealed trait UnifyError extends Error
object UnifyError {
  case class UnexpectedExpr(schema: SchemaChange) extends UnifyError {
    def message = "Unexpected expression: " + schema 
  }
  case object CouldNotPatchRoot extends UnifyError {
    def message = "Could not patch ROOT"
  }
  case object CannotObjectConcatExpr extends UnifyError {
    def message = "Cannot object concat an expression"
  }
  case object CannotArrayConcatExpr extends UnifyError {
    def message = "Cannot array concat an expression"
  }
}

/**
 * A `PipelineBuilder` consists of a list of pipeline operations in *reverse*
 * order and a patch to be applied to all subsequent additions to the pipeline.
 *
 * This abstraction represents a work-in-progress, where more operations are 
 * expected to be patched and added to the pipeline. At some point, the `build`
 * method will be invoked to convert the `PipelineBuilder` into a `Pipeline`.
 */
final case class PipelineBuilder private (buffer: List[PipelineOp], patch: MergePatch = MergePatch.Id, base: SchemaChange = SchemaChange.Init, struct: SchemaChange = SchemaChange.Init) { self =>
  import PipelineBuilder._
  import PipelineOp._
  import ExprOp.{DocVar, GroupOp}

  def build: Pipeline = Pipeline(MoveRootToField :: buffer.reverse)

  def schema: PipelineSchema = PipelineSchema(buffer.reverse)

  def unify(that: PipelineBuilder)(f: (DocVar, DocVar) => Error \/ PipelineBuilder): Error \/ PipelineBuilder = {
    import \&/._
    import cogroup.{Instr, ConsumeLeft, ConsumeRight, ConsumeBoth}

    def optRight[A, B](o: Option[A \/ B])(f: Option[A] => Error): Error \/ B = {
      o.map(_.fold(a => -\/ (f(Some(a))), b => \/- (b))).getOrElse(-\/ (f(None)))
    }

    lazy val step: ((SchemaChange, SchemaChange), PipelineOp \&/ PipelineOp) => 
           Error \/ ((SchemaChange, SchemaChange), Instr[PipelineOp]) = {
      case ((lschema, rschema), these) =>
        def delegate = step((rschema, lschema), these.swap).map {
          case ((lschema, rschema), instr) => ((rschema, lschema), instr.flip)
        }

        these match {
          case This(left) => 
            val rchange = SchemaChange.Init.nestField("right")

            for {
              rproj <- optRight(rchange.toProject)(_ => UnifyError.UnexpectedExpr(rchange))

              rschema2 = rschema.rebase(rchange)

              rec <- step((lschema, rschema2), Both(left, rproj))
            } yield rec
          
          case That(right) => 
            val lchange = SchemaChange.Init.nestField("left")

            for {
              lproj <- optRight(lchange.toProject)(_ => UnifyError.UnexpectedExpr(lchange))

              lschema2 = rschema.rebase(lchange)

              rec <- step((lschema2, rschema), Both(lproj, right))
            } yield rec

          case Both(g1 : GeoNear, g2 : GeoNear) if g1 == g2 => 
            \/- ((lschema, rschema) -> ConsumeBoth(g1 :: Nil))

          case Both(g1 : GeoNear, right) =>
            \/- ((lschema, rschema) -> ConsumeLeft(g1 :: Nil))

          case Both(left, g2 : GeoNear) => delegate

          case Both(left : ShapePreservingOp, _) => 
            \/- ((lschema, rschema) -> ConsumeLeft(left :: Nil))

          case Both(_, right : ShapePreservingOp) => 
            \/- ((lschema, rschema) -> ConsumeRight(right :: Nil))

          case Both(x @ Group(Grouped(g1_), b1), y @ Group(Grouped(g2_), b2)) if (b1 == b2) =>            
            val (to, _) = BsonField.flattenMapping(g1_.keys.toList ++ g2_.keys.toList)

            val g1 = g1_.map(t => (to(t._1): BsonField.Leaf) -> t._2)
            val g2 = g2_.map(t => (to(t._1): BsonField.Leaf) -> t._2)

            val g = g1 ++ g2

            val fixup = Project(Reshape.Doc(Map())).setAll(to.mapValues(f => -\/ (DocVar.ROOT(f))))

            \/- ((lschema, rschema) -> ConsumeBoth(Group(Grouped(g), b1) :: fixup :: Nil))

          case Both(x @ Group(Grouped(g1_), b1), _) => 
            val uniqName = BsonField.genUniqName(g1_.keys.map(_.toName))

            val tmpG = Group(Grouped(Map(uniqName -> ExprOp.Push(DocVar.ROOT()))), b1)

            for {
              t <- step((lschema, rschema), Both(x, tmpG))

              ((lschema, rschema), ConsumeBoth(emit)) = t
            } yield ((lschema, rschema.nestField(uniqName.value)), ConsumeLeft(emit :+ Unwind(DocVar.ROOT(uniqName))))

          case Both(_, Group(_, _)) => delegate

          case Both(p1 @ Project(_), p2 @ Project(_)) => 
            val Left  = BsonField.Name("left")
            val Right = BsonField.Name("right")

            val LeftV  = DocVar.ROOT(Left)
            val RightV = DocVar.ROOT(Right)

            \/- {
              ((lschema.nestField("left"), rschema.nestField("right")) -> 
               ConsumeBoth(Project(Reshape.Doc(Map(Left -> \/- (p1.shape), Right -> \/- (p2.shape)))) :: Nil))
            }

          case Both(p1 @ Project(_), right) => 
            val uniqName: BsonField.Leaf = p1.shape match {
              case Reshape.Arr(m) => BsonField.genUniqIndex(m.keys)
              case Reshape.Doc(m) => BsonField.genUniqName(m.keys)
            }

            val Left  = BsonField.Name("left")
            val Right = BsonField.Name("right")

            val LeftV  = DocVar.ROOT(Left)
            val RightV = DocVar.ROOT(Right)

            \/- {
              ((lschema.nestField("left"), rschema.nestField("right")) ->
                ConsumeLeft(Project(Reshape.Doc(Map(Left -> \/- (p1.shape), Right -> -\/ (DocVar.ROOT())))) :: Nil))
            }

          case Both(_, Project(_)) => delegate

          case Both(r1 @ Redact(_), r2 @ Redact(_)) => 
            \/- ((lschema, rschema) -> ConsumeBoth(r1 :: r2 :: Nil))

          case Both(u1 @ Unwind(_), u2 @ Unwind(_)) if u1 == u2 =>
            \/- ((lschema, rschema) -> ConsumeBoth(u1 :: Nil))

          case Both(u1 @ Unwind(_), u2 @ Unwind(_)) =>
            \/- ((lschema, rschema) -> ConsumeBoth(u1 :: u2 :: Nil))

          case Both(u1 @ Unwind(_), r2 @ Redact(_)) =>
            \/- ((lschema, rschema) -> ConsumeRight(r2 :: Nil))

          case Both(r1 @ Redact(_), u2 @ Unwind(_)) => delegate
        }
    }

    cogroup.statefulE(this.buffer.reverse, that.buffer.reverse)((this.base, that.base))(step).flatMap {
      case ((lschema, rschema), list) =>
        val left  = lschema.patchRoot(SchemaChange.Init)
        val right = rschema.patchRoot(SchemaChange.Init)

        (left |@| right) { (left0, right0) =>
          val left  = left0.fold(_ => DocVar.ROOT(), DocVar.ROOT(_))
          val right = right0.fold(_ => DocVar.ROOT(), DocVar.ROOT(_))

          f(left, right).map { builder =>
            PipelineBuilder(builder.buffer ::: self.buffer, MergePatch.Id, builder.base)
          }
        }.getOrElse(-\/ (UnifyError.CouldNotPatchRoot))
    }
  }

  private def rootRef: Error \/ DocVar = {
    base.patchRoot(SchemaChange.Init).map(_.fold(_ => DocVar.ROOT(), DocVar.ROOT(_))).map(\/- apply).getOrElse {
      -\/ (UnifyError.CouldNotPatchRoot)
    }
  }

  def makeObject(name: String): Error \/ PipelineBuilder = {
    for {
      rootRef <- rootRef
    } yield copy(
        buffer  = Project(Reshape.Doc(Map(BsonField.Name(name) -> -\/ (rootRef)))) :: buffer, 
        base    = SchemaChange.Init,
        struct  = struct.nestField(name)
      )
  }

  def makeArray: Error \/ PipelineBuilder = {
    for {
      rootRef <- rootRef 
    } yield copy(
        buffer  = Project(Reshape.Arr(Map(BsonField.Index(0) -> -\/ (rootRef)))) :: buffer, 
        base    = SchemaChange.Init,
        struct  = struct.nestIndex
      )
  }

  def objectConcat(that: PipelineBuilder): Error \/ PipelineBuilder = {
    (this.struct.simplify, that.struct.simplify) match {
      case (s1 @ SchemaChange.MakeObject(m1), s2 @ SchemaChange.MakeObject(m2)) =>
        def convert(root: DocVar) = (keys: Iterable[String]) => 
          keys.map(BsonField.Name.apply).map(name => name -> -\/ (root \ name))

        for {
          rez <-  this.unify(that) { (left, right) =>
                    val leftTuples  = convert(left)(m1.keys)
                    val rightTuples = convert(right)(m2.keys)

                    \/- {
                      new PipelineBuilder(
                        buffer = Project(Reshape.Doc((leftTuples ++ rightTuples).toMap)) :: Nil,
                        base   = SchemaChange.Init,
                        struct = SchemaChange.MakeObject(m1 ++ m2)
                      )
                    }
                  }
        } yield rez

      case _ => -\/ (UnifyError.CannotObjectConcatExpr)
    }
  }

  def arrayConcat(that: PipelineBuilder): Error \/ PipelineBuilder = {
    (this.struct.simplify, that.struct.simplify) match {
      case (s1 @ SchemaChange.MakeArray(l1), s2 @ SchemaChange.MakeArray(l2)) =>
        def convert(root: DocVar) = (keys: Iterable[Int]) => 
          keys.map(BsonField.Index.apply).map(index => index -> -\/ (root \ index))

        for {
          rez <-  this.unify(that) { (left, right) =>
                    val leftTuples  = convert(left)(0 until l1.length)
                    val rightTuples = convert(right)(l1.length until (l1.length + l2.length))

                    \/- {
                      new PipelineBuilder(
                        buffer = Project(Reshape.Arr((leftTuples ++ rightTuples).toMap)) :: Nil,
                        base   = SchemaChange.Init,
                        struct = SchemaChange.MakeArray(l1 ++ l2)
                      )
                    }
                  }
        } yield rez

      case _ => -\/ (UnifyError.CannotObjectConcatExpr)
    }
  }

  def projectField(name: String): Error \/ PipelineBuilder = 
    \/- (copy(base = base.nestField(name), struct = struct.projectField(name)))

  def projectIndex(index: Int): Error \/ PipelineBuilder = 
    \/- (copy(base = base.nestIndex, struct = struct.projectIndex(index)))

  def groupBy(that: PipelineBuilder): Error \/ PipelineBuilder = {
    ???
  }

  def grouped(expr: GroupOp): Error \/ PipelineBuilder = {
    ???
  }

  private def add0(op: PipelineOp): MergePatchError \/ PipelineBuilder = {
    for {
      t <- patch(op)

      (ops2, patch2) = t
    } yield copy(buffer = ops2.reverse ::: buffer, patch = patch2)
  }

  def + (op: PipelineOp): MergePatchError \/ PipelineBuilder = {
    for {
      t   <- MoveToExprField(op)
      r   <- addAll0(t._1)
      r   <- if (op.isInstanceOf[ShapePreservingOp]) \/- (r) else r.add0(MoveRootToField)
    } yield r
  }

  def ++ (ops: List[PipelineOp]): MergePatchError \/ PipelineBuilder = {
    type EitherE[X] = MergePatchError \/ X

    ops.foldLeftM[EitherE, PipelineBuilder](this) {
      case (pipe, op) => pipe + op
    }
  }

  private def addAll0(ops: List[PipelineOp]): MergePatchError \/ PipelineBuilder = {
    type EitherE[X] = MergePatchError \/ X

    ops.foldLeftM[EitherE, PipelineBuilder](this) {
      case (pipe, op) => pipe.add0(op)
    }
  }
  
  def patch(patch2: MergePatch)(f: (MergePatch, MergePatch) => MergePatch): PipelineBuilder = copy(patch = f(patch, patch2))

  def patchSeq(patch2: MergePatch) = patch(patch2)(_ >> _)

  def patchPar(patch2: MergePatch) = patch(patch2)(_ && _)

  def merge0(that: PipelineBuilder): MergePatchError \/ (PipelineBuilder, MergePatch, MergePatch) = mergeCustom(that)(_ >> _)

  def mergeCustom(that: PipelineBuilder)(f: (MergePatch, MergePatch) => MergePatch): MergePatchError \/ (PipelineBuilder, MergePatch, MergePatch) = {
    for {
      t <- PipelineMerge.mergeOps(Nil, this.buffer.reverse, this.patch, that.buffer.reverse, that.patch).leftMap(MergePatchError.Pipeline.apply)

      (ops, lp, rp) = t
    } yield (PipelineBuilder(ops.reverse, f(lp, rp)), lp, rp)
  }

  def merge(that: PipelineBuilder): MergePatchError \/ PipelineBuilder = merge0(that).map(_._1)
}
object PipelineBuilder {
  import PipelineOp._
  import ExprOp.{DocVar}

  private val ExprName = BsonField.Name("__sd_expr")
  private val ExprVar  = ExprOp.DocVar.ROOT(ExprName)

  private val MoveToExprField = MergePatch.Rename(ExprOp.DocVar.ROOT(), ExprVar)

  private val MoveRootToField = Project(Reshape.Doc(Map(ExprName -> -\/ (DocVar.ROOT()))))

  def empty = PipelineBuilder(Nil, MergePatch.Id)

  def apply(p: PipelineOp): MergePatchError \/ PipelineBuilder = empty + p

  implicit def PipelineBuilderRenderTree(implicit RO: RenderTree[PipelineOp]) = new RenderTree[PipelineBuilder] {
    override def render(v: PipelineBuilder) = NonTerminal("PipelineBuilder", v.buffer.reverse.map(RO.render(_)))
  }
}
