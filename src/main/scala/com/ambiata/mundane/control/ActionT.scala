package com.ambiata.mundane.control

import scala.util.control.NonFatal
import scalaz._, Scalaz._, \&/._

/**
 * A data type for holding computations that can fail with exceptions.
 * This is effectively a ReaderT > ErrorT > WriterT > F stack, with
 * a specialized error type. This particular specializtion handles
 * string/exception based failures and should be used to wrap up unsafe
 * apis (i.e. java code).
 *
 * This specialization exists for a number of reasons:
 *  - Basically because you can't use the stack directly via a type alias
 *    without incurring the wrath of scalac and the inference warlords.
 *  - The formulation lets us plug in a few things together to handle
 *    IO and other values of F, whilst keeping some level of sanity.
 *
 * NOTE: This is specifically formulated to not hit scalac bugzzzzz, change with caution.....
 */
case class ActionT[F[+_], W, R, +A](runT: R => ResultT[({ type l[+a] = WriterT[F, W, a] })#l, A]) {
  def map[B](f: A => B)(implicit W: Monoid[W], F: Functor[F]): ActionT[F, W, R, B] =
    ActionT(r => runT(r).map(f))

  def flatMap[B](f: A => ActionT[F, W, R, B])(implicit W: Monoid[W], F: Monad[F]): ActionT[F, W, R, B] =
    ActionT(r => runT(r).flatMap(a => f(a).runT(r)))

  def run(r: R): F[(W, Result[A])] =
    runT(r).run.run

  def execute(r: R)(implicit F: Functor[F]): F[Result[A]] =
    run(r).map({ case (w, a) => a })

  def executeT(r: R)(implicit F: Functor[F]): ResultT[F, A] =
    ResultT(execute(r))
}

object ActionT {
  def safe[F[+_]: Monad, W: Monoid, R, A](a: => A): ActionT[F, W, R, A] =
    ActionT(_ => ResultT.safe[({ type l[+a] = WriterT[F, W, a] })#l, A](a))

  def option[F[+_]: Monad, W: Monoid, R, A](a: => A): ActionT[F, W, R, Option[A]] =
    ActionT(_ => ResultT.option[({ type l[+a] = WriterT[F, W, a] })#l, A](a))

  def ok[F[+_]: Monad, W: Monoid, R, A](a: => A): ActionT[F, W, R, A] =
    ActionT(_ => ResultT.ok[({ type l[+a] = WriterT[F, W, a] })#l, A](a))

  def exception[F[+_]: Monad, W: Monoid, R, A](t: Throwable): ActionT[F, W, R, A] =
    ActionT(_ => ResultT.exception[({ type l[+a] = WriterT[F, W, a] })#l, A](t))

  def fail[F[+_]: Monad, W: Monoid, R, A](message: String): ActionT[F, W, R, A] =
    ActionT(_ => ResultT.fail[({ type l[+a] = WriterT[F, W, a] })#l, A](message))

  def error[F[+_]: Monad, W: Monoid, R, A](message: String, t: Throwable): ActionT[F, W, R, A] =
    ActionT(_ => ResultT.error[({ type l[+a] = WriterT[F, W, a] })#l, A](message, t))

  def these[F[+_]: Monad, W: Monoid, R, A](both: These[String, Throwable]): ActionT[F, W, R, A] =
    ActionT(_ => ResultT.these[({ type l[+a] = WriterT[F, W, a] })#l, A](both))

  implicit def ActionTMonad[F[+_]: Monad, W: Monoid, R]: Functor[({ type l[a] = ActionT[F, W, R, a] })#l] =
    new Monad[({ type l[a] = ActionT[F, W, R, a] })#l] {
      def bind[A, B](a: ActionT[F, W, R, A])(f: A => ActionT[F, W, R, B]) = a.flatMap(f)
      def point[A](a: => A) = ok[F, W, R, A](a)
    }
}

trait ActionTSupport[F[+_], W, R] {
  def safe[A](a: => A)(implicit M: Monad[F], W: Monoid[W]): ActionT[F, W, R, A] =
    ActionT(_ => ResultT.safe[({ type l[+a] = WriterT[F, W, a] })#l, A](a))

  def option[A](a: => A)(implicit M: Monad[F], W: Monoid[W]): ActionT[F, W, R, Option[A]] =
    ActionT(_ => ResultT.option[({ type l[+a] = WriterT[F, W, a] })#l, A](a))

  def ok[A](a: => A)(implicit M: Monad[F], W: Monoid[W]): ActionT[F, W, R, A] =
    ActionT(_ => ResultT.ok[({ type l[+a] = WriterT[F, W, a] })#l, A](a))

  def exception[A](t: Throwable)(implicit M: Monad[F], W: Monoid[W]): ActionT[F, W, R, A] =
    ActionT(_ => ResultT.exception[({ type l[+a] = WriterT[F, W, a] })#l, A](t))

  def fail[A](message: String)(implicit M: Monad[F], W: Monoid[W]): ActionT[F, W, R, A] =
    ActionT(_ => ResultT.fail[({ type l[+a] = WriterT[F, W, a] })#l, A](message))

  def error[A](message: String, t: Throwable)(implicit M: Monad[F], W: Monoid[W]): ActionT[F, W, R, A] =
    ActionT(_ => ResultT.error[({ type l[+a] = WriterT[F, W, a] })#l, A](message, t))

  def these[A](both: These[String, Throwable])(implicit M: Monad[F], W: Monoid[W]): ActionT[F, W, R, A] =
    ActionT(_ => ResultT.these[({ type l[+a] = WriterT[F, W, a] })#l, A](both))
}