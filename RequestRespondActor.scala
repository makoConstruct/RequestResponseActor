import collection.mutable.HashMap
import concurrent.{Promise, Future, ExecutionContext}
import util.{Try, Success, Failure}
import akka.actor._
import java.util.concurrent.Executor
import akka.pattern.ask
import scala.concurrent.duration._
import akka.util.Timeout

//When inheriting from some actor SomeActor, orElse its receive method, someActorReceive, into yours.

object RequestRespondActor{
	case class Response(returnId:Int, r:Any)
	case class Request(returnId:Int, r:Any)
	case class Ask(r:Any)
	case class Failed(returnId:Int, e:Throwable)
	implicit val bluntExecutionContext = ExecutionContext.fromExecutor( //just does it immediately on success, basically
		new Executor{  def execute(r:Runnable){r.run()}  })
	implicit val defaultTimeout = new Timeout(13.seconds)
	def outsiderQuery(s:ActorRef, r:Any):Future[Any] = s ? Ask(r)
}

trait RequestActor extends Actor{
	import RequestRespondActor._
	val expecting = new HashMap[Int, Promise[Any]]
	var returnId:Int = 0
	def query(s:ActorRef/*must be a RespondActor*/, r:Any):Future[Any] ={
		val pr = Promise[Any]()
		val id = returnId
		returnId += 1
		//TODO: Timeouts
		expecting(id) = pr
		s ! Request(id, r)
		pr.future
	}
	private def takeResponse(res:Response){
		val Response(returnId, r) = res
		expecting get returnId match{
			case Some(pr)=>
				pr success r
				expecting remove returnId
			case None => {} //log.error("received a response that wasn't requested")
		}
	}
	def takeFailed(f:Failed){
		val Failed(returnId, e) = f
		expecting get returnId match{
			case Some(pr)=>
				pr failure e
				expecting remove returnId
			case None => {} //log.error("received a response that wasn't requested(also, it's an error report)")
		}
	}
	def requestActorReceive:PartialFunction[Any,Unit] = {
		case r:Response=> takeResponse(r)
		case f:Failed=> takeFailed(f)
	}
}

trait RespondActor extends Actor{
	import RequestRespondActor._
	def takeRequest(req:Request){
		val Request(returnId, r) = req
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
	}
	def takeAsk(a:Ask){
		val Ask(r) = a
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
	}
	def respondActorReceive:PartialFunction[Any,Unit] = {
		case r:Request=> takeRequest(r)
		case a:Ask=> takeAsk(a)
	}
	//and a default pf for statements along the same vein:
	protected def respondToQueryWithFailure: PartialFunction[Any, Future[Any]] = { case _ => Future.failed(new RuntimeException("this actor has not implemented the takeQuery method, and thus cannot handle your query")) }
	def takeQuery: PartialFunction[Any, Future[Any]]
}

trait RequestRespondActor extends RequestActor with RespondActor{
	import RequestRespondActor._
	protected def requestReponseActorReceive:PartialFunction[Any,Unit] = requestActorReceive orElse respondActorReceive
}

