package vkdumper

import org.scalatest.{FlatSpec, Matchers}
import ApiData._

class ApiTest extends FlatSpec with Matchers {

  type DecFun[T] = String => Result[T]
  type Ref = (String, DecFun[Any], Result[Any])

  def dec[T : Manifest]: DecFun[T] =
    s => apiDecodeStr[T](Right(s), 200)

  def rr[T : Manifest](src: String, rf: Result[T]): Ref =
    (src, dec[T], rf)

  def ro[T : Manifest](src: String, o: T): Ref =
    rr(src, Res(o))

  "Api" should "parse incoming json" in {

    // todo test JValues

    val refs: List[Ref] = List(
      ro("""
        |{"response": {
        | "first_name": "a",
        | "last_name": "b",
        | "id": 1337
        |}}
      """.stripMargin, ApiUser("a", "b", 1337, None)),
      ro("""
          |{"response": {
          | "first_name": "aa",
          | "photo_100": "foobar",
          | "foo": "bar",
          | "last_name": "bb",
          | "id": 1337
          |}}
        """.stripMargin, ApiUser("aa", "bb", 1337, Some("foobar"))),
      ro("""
          |{"response": [
          | {"first_name": "u1", "last_name": "", "id": 1},
          | {"first_name": "u2", "last_name": "", "id": 2},
          | {"first_name": "u3", "last_name": "", "id": 3}
          |]}
        """.stripMargin, List(1, 2, 3).map(n => ApiUser(s"u$n", "", n, None))),
      rr("""
          |{"error": {
          | "error_msg": "foo", "error_code": 1337
          |}}
        """.stripMargin, ApiError(1337, "foo"))
    )

    refs.zipWithIndex.foreach {
      case ((json, decf, ref), n) =>
        println(s"Ref $n")
        decf(json) shouldBe ref
    }

  }

}
