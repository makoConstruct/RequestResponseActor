##rationalle: Akka asks are bad
Problem one: They create an entire actor to await the response. RequesterActors just create an entry in a map.

Problem two: You are not allowed to close over actor state when you respond to the future of an ask, due to the fact that the future an ask returns is not guaranteed to be executed synchronous with the actor message queue. That is ridiculous. You are an actor. Managing state is what you do. You should be able to do that at all times. Our futures are completed during a response to a message cue item, so you can mutate however you like during your thens and maps when you use our method.

See http://doc.akka.io/docs/akka/snapshot/scala/actors.html for more discussion of the afformentioned problems with the ask pattern.

###usage example:
```scala
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
```

###how does it work?
Basically, behind the futures and the promises, it transmits every request in a `Request(id:Int, content:Any)`, and when it receives `Response(id, result)` it completes the future that corresponds to id with `result`. It supplies a special implicit execution context to apply to callbacks attached to the futures waiting for a response message that ensures they're executed while the message is being processed, thus ensuring the Actor has exclusive access to its state during that time.
###have you tested this heavily enough to know that it's actually avoiding race conditions?
...n-no, sir...
###caveats?
Yeah I'd guess there's a *reason* this isn't default behavior in akka...

* Precludes the use the `receive` partialfunction method(you define takeQuery and takeStatement pfs instead), so you can't do the state machine thing. Transitioning to another actor state will actually silently break request-response behavior. I should probably make that less silent or provide my own partial function switching. I'd need to survey demand cause that's yet to appeal to me, personally.

* Methods executed during responses to futures(like `then`, `map`) will block the actor due to the implicit blunt execution context, meaning in the rare case when you want to `Future{ ... }` an operation off into a separate thread, it will happen in the actor thread instead. Calling `Future({ ... }, scala.concurrent.ExecutionContext.global)` in these cases will provide the expected behavior.

Of course, I don't consider either of these significant enough to put up with the default.