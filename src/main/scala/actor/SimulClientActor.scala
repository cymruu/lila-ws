package lila.ws

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ Behavior, PostStop }

import ipc.*

object SimulClientActor:

  import ClientActor.*

  case class State(
      room: RoomActor.State,
      site: ClientActor.State = ClientActor.State()
  )

  def start(roomState: RoomActor.State, fromVersion: Option[SocketVersion])(
      deps: Deps
  ): Behavior[ClientMsg] =
    Behaviors.setup { ctx =>
      RoomActor.onStart(roomState, fromVersion, deps, ctx)
      apply(State(roomState), deps)
    }

  private def apply(state: State, deps: Deps): Behavior[ClientMsg] =
    Behaviors
      .receive[ClientMsg] { (ctx, msg) =>
        import deps.*

        def receive: PartialFunction[ClientMsg, Behavior[ClientMsg]] =

          case in: ClientIn =>
            clientInReceive(state.site, deps, in) match
              case None    => Behaviors.same
              case Some(s) => apply(state.copy(site = s), deps)

          case ClientCtrl.Broom(oldSeconds) =>
            if (state.site.lastPing < oldSeconds) Behaviors.stopped
            else
              keepAlive.simul(state.room.id)
              Behaviors.same

          case ctrl: ClientCtrl => socketControl(state.site, deps, ctrl)

          // default receive (site)
          case msg: ClientOutSite =>
            val siteState = globalReceive(state.site, deps, ctx, msg)
            if (siteState == state.site) Behaviors.same
            else apply(state.copy(site = siteState), deps)

          case _ =>
            Monitor.clientOutUnhandled("simul").increment()
            Behaviors.same

        RoomActor.receive(state.room, deps).lift(msg).fold(receive(msg)) { case (newState, emit) =>
          emit foreach lilaIn.simul
          newState.fold(Behaviors.same[ClientMsg]) { roomState =>
            apply(state.copy(room = roomState), deps)
          }
        }

      }
      .receiveSignal { case (ctx, PostStop) =>
        onStop(state.site, deps, ctx)
        RoomActor.onStop(state.room, deps, ctx)
        Behaviors.same
      }
