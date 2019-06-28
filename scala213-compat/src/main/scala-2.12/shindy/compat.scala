package shindy

object compat {
  type LazyList[+T] = Stream[T]

  object LazyList {
    def from(start: Int): LazyList[Int] = Stream.from(start)
  }
}
