package com.ambiata.mundane.parse

import com.ambiata.mundane.csv._
import scalaz._, Scalaz._
import org.joda.time._
import format.DateTimeFormat
import scalaz.Failure
import scalaz.Success
import ListParser._
import scalaz.Validation.FlatMap._

/**
 * Parser for a list of strings, returning a Failure[String] if the parse failed, or an object A
 */
case class ListParser[A](parse: (Int, List[String]) => ParseResult[A]) {
  def parse(input: List[String]): ParseResult[A] =
    parse(0, input)

  def run(input: List[String]): Validation[String, A] =
    parse(input) match {
      case Success(s) =>
        s match {
          case (_, Nil, a) => a.success
          case (p, x, _)   =>
            s"""|Parsed successfully: $input up to position $p
                | -> but the rest of the list was not consumed: $x""".stripMargin.failure

        }

      case Failure((i, f)) =>
        input match {
          case Nil => Failure(f)
          case head :: rest => (input.mkString(", ") + "\n" + f + s" (position: $i)").failure
        }

    }

  def preprocess(f: String => String): ListParser[A] =
    ListParser[A]((i: Int, list: List[String]) => parse(i, list.map(f)))

  def map[B](f: A => B): ListParser[B] =
    flatMap(a => ListParser.value(f(a).success))

  def flatMap[B](f: A => ListParser[B]): ListParser[B] =
    ListParser((position, state) =>
      parse(position, state) match {
        case Success((nuposition, nustate, a)) => f(a).parse(nuposition, nustate)
        case Failure(error)                    => Failure(error)
      })

  def satisfies(f: A => Boolean): ListParser[A] =
    ListParser((n, ls) =>
      parse(n, ls) match {
        case s@ Success((nuposition, nustate, a)) => if (f(a)) {
            s
          } else Failure((n + 1, "Value doesn't satisfy supposition"))
        case f @ Failure(_) => f
      })

  def named(n: String): ListParser[A] =
    ListParser((position, state) =>
      parse(position, state) match {
        case Failure((p, message)) => Failure((p, message+s" (for $n)"))
        case success               => success
      })

  def whenEmpty(a: A): ListParser[A] =
    ListParser.empty(a) ||| this

  def nonempty(implicit ev: A =:= String) =
    flatMap(a => ListParser((position, state) =>
      if (ev(a).isEmpty) (position, s"Expected string at position $position to be non empty").failure
      else (position, state, a).success
    ))

  def oflength(len: Int)(implicit ev: A =:= String) =
    flatMap(a => ListParser((position, state) =>
      if (ev(a).length != len) (position, s"Expected string at position $position to be of length $len").failure
      else (position, state, a).success
    ))

  def oflength(from: Int, to: Int)(implicit ev: A =:= String) =
    flatMap(a => ListParser((position, state) =>
      if (ev(a).length < from || ev(a).length > to) (position, s"Expected string at position $position to be of length between $from and $to").failure
      else (position, state, a).success
    ))

  def oflengthifsome(len: Int)(implicit ev: A =:= Option[String]) =
    flatMap(a => ListParser((position, state) => ev(a) match {
      case None    => (position, state, None).success
      case Some(x) if (x.length == len) => (position, state, Some(x)).success
      case Some(x) => (position, s"Expected the optional string at position $position to be of length $len if it exists").failure
    }))

  def oflengthifsome(from: Int, to: Int)(implicit ev: A =:= Option[String]) =
    flatMap(a => ListParser((position, state) => ev(a) match {
      case None    => (position, state, None).success
      case Some(x) if (x.length >= from && x.length <= to) => (position, state, Some(x)).success
      case Some(x) => (position, s"Expected the optional string at position $position to be of length between $from and $to if it exists").failure
    }))

  def option: ListParser[Option[A]] =
    ListParser((position, state) => state match {
      case "" :: t => (position + 1, t, None).success
      case xs => parse(position, xs).map(_.map(Option.apply[A]))
    })

  def commaDelimited: ListParser[List[A]] =
    delimited(',')

  def delimited(delimiter: Char): ListParser[List[A]] =
    ListParser.delimitedValues(this, delimiter)

  def bracketed(opening: Char, closing: Char): ListParser[A] =
    ListParser.bracketed(this, opening, closing)

  def |||(x: ListParser[A]): ListParser[A] =
    ListParser((n, ls) =>
      parse(n, ls) match {
        case s @ Success(_) => s
        case Failure(_)     => x.parse(n, ls)
      })

  def * : ListParser[List[A]] =
    this.+ ||| success(Nil)

  def + : ListParser[List[A]] = for {
    x <- this
    xs <- this *
  } yield x :: xs
}

/**
 * Standard List parsers
 */
object ListParser {
  type ParseResult[A] = Validation[(Int, String), (Int, List[String], A)]

  /** The parser that always succeeds with the specified value. */
  def success[A](a: A): ListParser[A] =
    ListParser((position, state) => (position, state, a).success)

  /** The parser that always fails. */
  def fail[A](message: String): ListParser[A] =
    ListParser((position, state) => (position, message).failure)

  /**
   * a parser returning the current position (1-based) but does not consume any input
   * If the input has no elements the position is 0
   */
  def getPosition: ListParser[Int] =
    ListParser((position, state) => (position, state, position).success)

  /** A convenience function for cunstructoring parsers from scalaz style parseX functions. */
  def parseWithType[E, A](p: String => Validation[E, A], annotation: String): ListParser[A] =
    string.flatMap(s => value(p(s).leftMap(_ => s"""$annotation: '$s'""")))

  /** A convenience function for custom string parsers */
  def parseAttempt[A](p: String => Option[A], annotation: String): ListParser[A] =
    parseWithType(s => p(s).toSuccess(()), annotation)

  /** A byte, parsed accoding to java.lang.Byte.parseByte */
  def byte: ListParser[Byte] =
    parseWithType(_.parseByte, "not a byte")

  /** A short, parsed accoding to java.lang.Short.parseShort */
  def short: ListParser[Short] =
    parseWithType(_.parseShort, "not a short")

  /** An int, parsed accoding to java.lang.Integer.parseInt */
  def int: ListParser[Int] =
    parseWithType(_.parseInt, "not an int")

  /** A long, parsed accoding to java.lang.Long.parseLong */
  def long: ListParser[Long] =
    parseWithType(_.parseLong, "not a long")

  /** A double, parsed accoding to java.lang.Double.parseDouble */
  def double: ListParser[Double] =
    parseWithType(_.parseDouble, "not a double")

  /** A boolean, parsed accoding to java.lang.Boolean.parseBoolean */
  def boolean: ListParser[Boolean] =
    parseWithType(_.parseBoolean, "not a boolean")

  /** A char, the head of a single character string */
  def char: ListParser[Char] =
    parseAttempt(s => s.headOption.filter(_ => s.length == 1), "Not a char")

  /** A char which can only be one of the given characters  */
  def charFlag(valid: List[Char]): ListParser[Char] = for {
    c <- char
    f <- if(valid.contains(c)) success(c) else fail(s"Unknown flag '${c}', expected one of '${valid.mkString(",")}'")
  } yield f

  /** Exactly one token, can only fail if the input is empty. */
  def string: ListParser[String] =
    ListParser((pos, str) => str match {
      case h :: t => (pos + 1, t, h).success
      case Nil    => (pos, s"not enough input, expected more than $pos fields.").failure
    })

  /** Possibly one token, or [[None]] if exhausted */
  def stringOpt: ListParser[Option[String]] =
    string.map(_.some) ||| none.pure[ListParser]

  def debug(tag: String): ListParser[Unit] =
    ListParser((position, state) => {
      println(s"[$tag] ${position}, ${state}")
      (position, state, ()).success
    } )

  /**
   * A parser for a local date with a given format, where format means joda time
   * supported formats: http://joda-time.sourceforge.net/apidocs/org/joda/time/format/DateTimeFormat.html
   */
  def localDateFormat(format: String): ListParser[LocalDate] =
    string.flatMap(s => valueOr(DateTimeFormat.forPattern(format).parseLocalDate(s),
                                _ => s"""not a local date with format $format: '$s'"""))

  /**
   * A parser for a local date-time with a given format, where format means joda time
   * supported formats: http://joda-time.sourceforge.net/apidocs/org/joda/time/format/DateTimeFormat.html
   */
  def localDatetimeFormat(format: String): ListParser[LocalDateTime] =
    string.flatMap(s => valueOr(DateTimeFormat.forPattern(format).parseLocalDateTime(s),
                                _ => s"""not a local date time with format $format: '$s'"""))

  /**
   * A parser for a local date with the `yyyy-MM-dd` format.
   */
  def localDate: ListParser[LocalDate] =
    localDateFormat("yyyy-MM-dd")

  /**
   * A parser for a local date with the `dd-MM-yyyy HH:mm:ss` format
   */
  def localDateTime: ListParser[LocalDateTime] =
    localDatetimeFormat("yyyy-MM-dd HH:mm:ss")

  /**
   * A parser for a value of type A
   */
  def value[A](f: => Validation[String, A]): ListParser[A] =
    ListParser((position, str) => f.bimap((position,_), (position, str, _)))

  /**
   * A parser for a value of type A with a failure message in case of an exception
   */
  def valueOr[A](a: => A, failure: Throwable => String): ListParser[A] =
    value(Validation.fromTryCatchNonFatal(a).leftMap(failure))

  /**
   * A parser consuming n positions in the input
   */
  def consume(n: Int): ListParser[Unit] =
    ListParser((pos, str) => {
      val nupos = pos + n
      str.length match {
        case l if n <= l => (nupos, str.slice(n, l), ()).success
        case _           => (nupos, s"not enough input, expected more than $nupos.").failure
      }
    })

  /**
   * A parser consuming all remaining fields
   */
  def consumeRest: ListParser[Unit] =
    ListParser((pos, str) => (str.length, Nil, ()).success)

  /**
   * A parser for a delimited pair of values
   *
   * It uses the SimpleCsv parser which will *not* reject quoted fields containing unquoted quotes
   */
  def pair[A, B](pa: ListParser[A], pb: ListParser[B], delimiter: Char): ListParser[(A, B)] =
    csvPair(pa, pb, SimpleCsv.delimited(delimiter))

  /**
   * A parser for a pair of values which can be read as comma-pipe-tab-separated
   */
  def csvPair[A, B](pa: ListParser[A], pb: ListParser[B], parser: CsvParser): ListParser[(A, B)] = {
    ListParser((position, state) => state match {
      case h :: t =>
        if (h.isEmpty) (position, s"Expected string at position $position to be non empty").failure
        else
          csvResultToParseResult(position, parser.parse(h)).flatMap {
            case a :: b :: Nil =>
              (pa.run(List(a)) |@| pb.run(List(b)))((a,b) => (position + 1, t, a -> b )).leftMap(e => position -> e)
            case other => (position, s"$other cannot be parsed as a pair").failure
          }
      case Nil => (position, s"Expected string at position $position to be non empty").failure
    })
  }

  /**
   * A parser for a list delimited values of the same type
   *
   * It uses the SimpleCsv parser which will *not* reject quoted fields containing unquoted quotes
   */
  def delimitedValues[A](p: ListParser[A], delimiter: Char): ListParser[List[A]] =
    csvValues(p, SimpleCsv.delimited(delimiter))

  /**
   * A parser for csv values
   */
  def csvValues[A](p: ListParser[A], parser: CsvParser): ListParser[List[A]] = {
    ListParser((position, state) => state match {
      case h :: t =>
        import scala.annotation.tailrec
        @tailrec def traverseListParser[B](p: ListParser[B], ls: List[String], as: List[B]): Validation[String, List[B]] = ls match {
          case h1 :: t1 => p.run(h1.pure[List]) match {
            case Success(a)   => traverseListParser(p, t1, a :: as)
            case e@Failure(_) => e
          }
          case _ => Success(as.reverse)
        }

        if (h.isEmpty) (position + 1, t, Nil).success
        else
          csvResultToParseResult(position, parser.parse(h)).flatMap { splitList =>
            val parsed = traverseListParser[A](p, splitList, Nil)
            parsed.map { case as => (position + 1, t, as) }.leftMap((position, _))
          }
      case Nil => (position, Nil, Nil).success
    })
  }

  /** transform the result of parsing csv fields into a ListParse result */
  def csvResultToParseResult(position: Int, result: String \/ List[String]): Validation[(Int, String), List[String]] =
    Validation.fromEither(result.toEither.leftMap((position, _)))

  /**
   * A parser for a value that is surrounded by 2 other characters
   */
  def bracketed[A](parser: ListParser[A], opening: Char, closing: Char): ListParser[A] =
    ListParser { (position, state) =>
      state match {
        case s :: rest if s.startsWith(opening.toString) && s.endsWith(closing.toString) =>
          parser.parse(position, s.drop(1).dropRight(1) :: rest)

        case other =>
          (position, s"The current string to parse is not bracketed by $opening, $closing: $state (at position: $position)").failure
      }
    }

  /**
   * A parser for key value maps
   */
  def keyValueMap[K, V](key: ListParser[K], value: ListParser[V], entriesDelimiter: Char, keyValueDelimiter: Char): ListParser[Map[K, V]] =
    delimitedValues(pair[K, V](key, value, keyValueDelimiter), entriesDelimiter).map(_.toMap)

  /**
   * A parser for key value maps with json delimiters
   */
  def jsonKeyValueMap[K, V](key: ListParser[K], value: ListParser[V]): ListParser[Map[K, V]] =
    keyValueMap(key, value, ',', ':')

  /**
   * A parser that succeeds on an empty list and returns a value instead
   */
  def empty[A](a: A): ListParser[A] =
    ListParser((position, state) =>
      if (state.isEmpty) (position, Nil, a).success
      else (position, s"$state is not empty").failure)

  /**
   * A parser that succeeds on an empty list and returns an empty string
   */
  def emptyString: ListParser[String] =
    empty("")

  /**
   * A parser that succeeds on an empty list and returns an empty sequence
   */
  def emptyList[A]: ListParser[List[A]] =
    empty(Nil)

  /**
   * A parser that returns 0.0 on an empty list or parses a Double
   */
  def doubleOrZero: ListParser[Double] =
    empty(0.0) ||| double

  /**
   * A parser that returns 0 on an empty list or parses an Int
   */
  def intOrZero: ListParser[Int] =
    empty(0) ||| int

  /** parser for string enumerations */
  def oneOf(name: String, names: String*): ListParser[String] =
    oneOfList(name +: names.toList)

  /** parser for string enumerations */
  def oneOfList(names: List[String]): ListParser[String] = {
    parseAttempt(s => if (names.contains(s)) Some(s) else None, s"${names.mkString(",")} does not contain")
  }

  implicit def ListParserMonad: Monad[ListParser] = new Monad[ListParser] {
    def bind[A, B](r: ListParser[A])(f: A => ListParser[B]) = r flatMap f
    def point[A](a: => A) = value(a.success)
  }
}
