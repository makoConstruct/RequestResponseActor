import collection.mutable.HashMap
import concurrent.{Promise, Future, ExecutionContext}
import util.{Try, Success, Failure}
import akka.actor._
import java.util.concurrent.Executor
import akka.pattern.ask
import scala.concurrent.duration._
import akka.util.Timeout


private case class Response(returnId:Int, r:Any)
private case class Request(returnId:Int, r:Any)
private case class Ask(r:Any)
private case class Failed(returnId:Int, e:Throwable)

object RequestResponseActor{
	implicit val bluntExecutionContext = ExecutionContext.fromExecutor( //just does it immediately on success, basically
		new Executor{  def execute(r:Runnable){r.run()}  })
	implicit val defaultTimeout = new Timeout(13.seconds)
	def outsiderQuery(s:ActorRef, r:Any):Future[Any] = s ? Ask(r)
}

trait RequestResponseActor extends Actor with ActorLogging{
	import RequestResponseActor._
	implicit val bluntExecutionContext = RequestResponseActor.bluntExecutionContext //why is doing this necessary?..
	var returnId:Int = 0
	val expecting = new HashMap[Int, Promise[Any]]
	def query(s:ActorRef/*must also be requestresponseactor*/, r:Any):Future[Any] ={
		val pr = Promise[Any]()
		val id = returnId
		returnId += 1
		//TODO: Timeouts
		expecting(id) = pr
		s ! Request(id, r)
		pr.future
	}
	def receive = {
		case Response(returnId, r)=>
			expecting get returnId match{
				case Some(pr)=>
					pr success r
					expecting remove returnId
				case None => log.error("received a response that wasn't requested")
			}
		case Failed(returnId, e)=>
			expecting get returnId match{
				case Some(pr)=>
					pr failure e
					expecting remove returnId
				case None => log.error("received a response that wasn't requested")
			}
		case Request(returnId, r)=>
			val fr = takeQuery.applyOrElse(
				r,
				{_:Any => Future.failed(new RuntimeException("the takeQuery method of the queried actor did not handle the given type"))}
			)
			val csen = sender
			fr onComplete {
				case Success(tr) =>
					csen ! Response(returnId, tr)
				case Failure(e) =>
					csen ! Failed(returnId, e)
			}
		case Ask(r)=>
			val fr = takeQuery.applyOrElse(
				r,
				{_:Any => Future.failed(new RuntimeException("the takeQuery method of the queried actor did not handle the given type"))}
			)
			val csen = sender
			fr onComplete {
				case Success(tr) =>
					csen ! tr
				case Failure(e) =>
					csen ! e //No better way of handling this. Oh akka...
			}
		case o:Any =>
			if(takeStatement.isDefinedAt(o)) takeStatement(o)
	}
	//If you're making queries but not taking any, you may wish to just redirect your takeQuery to a stub that simply responds with an error. Here is one:
	def respondToQueryWithFailure: PartialFunction[Any, Future[Any]] = { case _ => Future.failed(new RuntimeException("this actor has not implemented the takeQuery method, and thus cannot handle your query")) }
	def takeStatement: PartialFunction[Any, Unit]
	def takeQuery: PartialFunction[Any, Future[Any]]
	// def takeStatement: PartialFunction[Any, Unit] = { case _=> () }
}

