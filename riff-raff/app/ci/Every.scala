package ci

import rx.lang.scala.Observable

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

object Every {

  def apply[T](frequency: Duration)
              (buildRetriever: => Observable[T])
              (implicit ec: ExecutionContext): Observable[T] = {
    (for {
      _ <- Observable.interval(1.second, frequency)
      builds <- buildRetriever
    } yield builds).publish.refCount
    // publish.refCount turns this from a 'cold' to a 'hot' observable (http://www.introtorx.com/content/v1.0.10621.0/14_HotAndColdObservables.html)
    // i.e. however many subscriptions, we only make one set of API calls
  }
}

trait ContinuousIntegrationAPI {
  def jobs(implicit ec: ExecutionContext): Observable[Job]
  def builds(job: Job)(implicit ec: ExecutionContext): Observable[CIBuild]
  def succesfulBuildBatch(job: Job)(implicit ec: ExecutionContext): Observable[Iterable[CIBuild]]
  def tags(build: CIBuild)(implicit ec: ExecutionContext): Future[Option[List[String]]]
}
