# fairflow

N.B. This library is in an our early, state. Therefore, I have not released it on clojars. If you want to use it as is, your best bet is to fork the repo. 


TODO Deploy to clojars: https://github.com/technomancy/leiningen/blob/stable/doc/DEPLOY.md.


A Clojure library designed to control generic workflows via configuration. The core engine controls movement from one state to another based on generic events, executing generic actions at each step.  I use the word generic several times there, to emphasize that aspects of...

* How events are received
* How flows are triggered
* How actions are processed
* How state is stored

... are all abstracted from the core.  

The `fair.contrib` package contains examples (or useable implementations) for some of those abstractions, so that this library is useable out of the box.

## How it works

Because it's very generic, a lot of this can be changed.  But the general idea is this:

You start with a configuration that describes each Flow and the steps in it. That config can be in a file (e.g. YAML or JSON), or a data store-- I have a Google Sheets based config for example.

You start one or more flows either by name, or by matching a trigger string.  Each Step is associated with a Step function which is executed when you enter that Step and on `callbacks` to the Step.  You can think of the flows as a graph and each Step as a node in that graph. When a Step is executed, it receives the event that triggered it, session state and bunch of Context.  It returns a Record that can include all or none of: actions, transitions, state mutations.

Actions are then handed off to a configurable set of Handlers. For example, a handler might send a message to Slack.  Or, another handler, might trigger creation of a message on a Stream or Queue (like SQS or Kafka).

State mutations comes in two varieties.  The first is state that is global to a Session (shared across all Steps and Flows for that session). You get to define what a Session is and how they are started-- you just need a unique session-id to keep them distinct. The type of state is specific to a single node for a session (a Step in a Flow for one session), this state is useful for the implementation of your Step logic. The persistence (or lack thereof, if you want memory only state) is, of course, abstracted, so you can choose your backend.  E.g. Dynamodb, Datomic, memory, etc.

Transitions are data elements that tell the engine how to progress from one State to another. The simplest case is, for example, a step that returns {:transition "flow-a.step-1"}, which is simply the Step naming the next flow/step that should execute. There are other possibilities, such as `_next`, `_terminal` (i.e. stop this flow), or a Map of transition strings to other steps/flows via configuration.

Ideally, your Step functions are pure and side-effect free. The update state and impact the world by returning the Actions, transitions and state-mutations described above. This has the usual benefits for testing, debugging, and re-usability.

## Is this a bot toolkit?

It could be used for that, but I've tried to abstract those parts away. So if you want to use it to control a Slack bot, for example, that's doable, and that's one of the way's I've used it.  I've also used it to control user flows for a mobile app via a REST endpoint.

### Why?

This is not a botkit that determines user intent via NLP. Although, you could connect it to any library or service that offers NLP and intent determination and then use it to control the rest of the flow.

Many current "bot" offerrings follow a simple model, where user says something, the toolkit determines intent and then chooses a thing to respond with. This is very much open-ended and user driven. Such an interface is fine for many use cases, but other systems require a more app driven flow; e.g. some sort of interview or wizard-type experience or business approval workflow. This engine was defined for those use cases.


## Usage

Run the sample app:
  
    lein run -m fair.flow-contrib.sample.app ./sample-flows.yml

## Thank You

I want to thank fairhomemaine.com for letting me share this source code as open source.

Also, a thank you to Frame.ai, where I worked out a great many of the ideas about how to specify workflows while developing a similar system in Python.

## Support 

Reach out if you need help.  You can create an issue on github, or DM me on Slack @mlimotte on `clojurians.slack.com`.

## License

Copyright © 2018 fairhomemaine.com (aka Skipp), Marc Limotte

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
