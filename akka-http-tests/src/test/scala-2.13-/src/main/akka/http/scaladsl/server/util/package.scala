package akka.http.scaladsl.server

package object util {
  type VarArgsFunction1[-T, +U] = (T*) ⇒ U
}
