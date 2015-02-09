##rationalle: Akka asks are bad
Problem one: They create an entire actor to await the response. RequesterActors just create an entry in a map.

Problem two: You are not allowed to close over actor state when you respond to the future of an ask, due to the fact that the future an ask returns is not guaranteed to be executed synchronous with the actor message queue. That is ridiculous. You are an actor. Managing state is what you do. You should be able to do that at all times. Our futures are completed during a response to a message cue item, so you can mutate however you like during your thens and maps when you use our method.

See http://doc.akka.io/docs/akka/snapshot/scala/actors.html for more discussion of the afformentioned problems with the ask pattern.

###usage example:
```scala
class SimpletonConsultant extends RequestRespondActor{
  var simpletonesCounter = 0

  def takeQuery(r:Any):Future[Any] = {
    case WhatIsYourCounterAt => Future.success(counter)
    case IsSallyReady(sally) => query(sally, AreYouReadySally)
  }  //note that it is a partial function, it doesn't have to have a default case. The future returned by a query to this actor will be failed if no results match.
  
  def receive:PartialFunction[Any,Unit] = requestRespondActorReceive orElse {
    case AskSallyWhetherYouShouldIncrementYourCounter(sally) =>
      //we query sally, we can do this because she is also a RequestResponseActor
      query(sally, ShouldIIncrementMyCounter) onSuccess {
        case b:Boolean => if(b) counter += 1 //is allowed to alter state, as this is executed while processing the return message from sally
      }
    case _ =>
  }
  
}

//this is how you query from a standpoint that is not a RequestResponseActor. Unlike with Akka, the syntax is not the same as a query from inside the system. This is intentional. The fact that Akka's ask syntax is the same from in and from out is actually kinda problematic cause it necessitates the use of a default implicit parameter (the sender ActorRef), which is an anti-pattern that'll getcha in the worst ways.
RequestResponseActor.outsiderQuery(aRefToSimpletonConsultant, WhatIsYourCounterAt) onSuccess { case i:Int =>
  println("it's at "+i)
}
```

###how does it work?
Basically, behind the futures and the promises, it transmits every request in a `Request(id:Int, content:Any)`, and when it receives `Response(id, result)` it completes the future that corresponds to `id` with the value of `result`. It's also capable of transmitting failures, while as far as I can tell, akka can only register query timeouts. The RequestResponseActor supplies a special implicit execution context to apply to callbacks attached to the futures waiting for a Response message. This blunt execution context ensures they're executed while the Response message is being processed, thus ensuring the Actor has exclusive access to its state during that time.
###caveats?
Methods executed during responses to futures(like `then`, `map`) will block the actor due to the implicit blunt execution context, meaning in the rare case when you want to `Future{ ... }` an operation off into a separate thread, it will happen in the actor thread instead. Calling `Future({ ... }, scala.concurrent.ExecutionContext.global)` in these cases will provide the expected behavior.

Goes without saying; *I* don't consider this issue to be significant enough to put up with akka's default provisions.