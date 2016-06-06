/*
 * Copyright 2014–2016 SlamData Inc.
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

package matryoshka

import scalaz._
import scalaz.syntax.monad._

sealed class CoalgebraOps[F[_], A](self: Coalgebra[F, A]) {
  def generalize[M[_]: Monad](implicit F: Functor[F]): GCoalgebra[M, F, A] =
    self(_).map(_.map(_.point[M]))

  def generalizeM[M[_]: Monad]: CoalgebraM[M, F, A] = self(_).point[M]

  def generalizeElgot[M[_]: Monad]: CoalgebraM[M, F, A] = self.generalizeM
}
