import collection.mutable.HashMap
import concurrent.{Promise, Future, ExecutionContext}
import util.{Try, Success, Failure}
import akka.actor.{Actor, ActorRef}
import java.util.concurrent.Executor
import java.lang.Runnable

//rationalle: Asks harmful.
// Problem one: They create an entire actor to await the response. RequesterActors just create an entry in a map.
// Problem two: You may not close over actor state when you respond to the future of an ask, due to the fact that the future an ask returns is not guaranteed to be executed synchronous with the actor message queue. That is ridiculous. You are an actor. Managing state is what you do. You can totally do that with our futures because they're completed during the reciept of a special kind of response message

//usage example:
/*
class SimpletonConsultant extends RequestResponseActor{
  var counter = 0

  def takeStatement = {
    case AskSallyWhetherYouShouldIncrementYourCounter(sally) =>
      query(sally, ShouldIIncrementMyCounter) onSuccess {
        case b:Boolean => if(b) counter += 1 //is allowed to alter state, as this is executed while processing the return message from sally
      }
    case _ =>
  }
  
  def takeQuery(r:Any):Future[Any] = {
    case WhatIsYourCounterAt => Future.success(counter)
    case IsSallyReady(sally) => query(sally, AreYouReadySally)
  }  //note that it is a partial function, it doesn't have to have a default cause. The future 
}
*/
// 
//how does it work?
// Basically, behind the futures and the promises, it transmits every request in a `Request(id:Int, content:Any)`, and when it receives `Response(id, result)` it completes the appropriate future with `result`. It supplies a special implicit execution context to apply to callbacks attached to the futures waiting for a response message that ensures they're executed while the message is being processed, thus ensuring the Actor has exclusive access to its state during that time.
//have you tested this heavily enough to know that it's actually avoiding race conditions?
// ...n-no, sir...
//caveats?
// Precludes the use the `receive` partialfunction method(you define takeQuery and takeStatement pfs instead), so you can't do the state machine thing. Transitioning to another actor state will actually silently break request-response behavior. I should probably make that less silent or provide my own partial function switching. I'd need to survey demand cause that's yet to appeal to me, personally.
// Methods executed during responses to futures(like `then`, `map`) will block the actor due to the implicit blunt execution context, meaning in the rare case when you want to `Future{}` an operation off into a separate thread, it will happen in the actor thread instead. Calling `Future({}, globalExecutionContext)` in these cases will provide the expected behavior.


object RequestResponseActor{
	private case class Response(returnId:Int, r:Any)
	private case class Request(returnId:Int, r:Any)
	private case class Failed(returnId:Int, e:Exception)
	implicit val bluntExecutionContext = ExecutionContext.fromExecutor( //just does it immediately on success, basically
		new Executor{  def execute(r:Runnable){r.run()}  })
}

trait RequestResponseActor extends Actor{
	import RequestResponseActor._
	implicit val becon = RequestResponseActor.bluntExecutionContext
	var returnId:Int = 0
	val expecting = new HashMap[Int, Promise[Any]]
	def query(s:ActorRef/*must also be requestresponseactor*/, r:Any):Future[Any] ={
		val pr = Promise[Any]()
		val id = returnId
		returnId += 1
		expecting(id) = pr
		s ! Request(id, r)
		pr.future
	}
	def takeStatement: PartialFunction[Any, Unit]
	def receive = {
		case Response(returnId, r)=>
			expecting get returnId match{
				case Some(pr)=>
					pr success r
					expecting remove returnId
				case None => Logger.error("response not requested")
			}
		case Failed(returnId, e)=>
			expecting get returnId match{
				case Some(pr)=>
					pr failure e
					expecting remove returnId
				case None => Logger.error("response not requested")
			}
		case Request(returnId, r)=>
			val fr = takeQuery.applyOrElse(
				r,
				{_ => Future.failure(new RuntimeException("the takeQuery method of the queried actor did not handle the given type"))}
			)
			fr.foreach{ tr:Any =>
				returnAdr ! Response(r.returnId, tr) }
			fr.recover{ e:Exception =>
				returnAdr ! Failed(r.returnId, e) }
		case o:Any =>
			if(takeStatement.isDefinedAt(o)) takeStatement(o)
	}
	def takeQuery: PartialFunction[Any, Future[Any]]
}