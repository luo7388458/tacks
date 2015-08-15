package actors

import scala.concurrent.duration._
import scala.concurrent.Future
import play.api.Play.current
import play.api.libs.concurrent.Akka
import play.api.libs.concurrent.Execution.Implicits._
import akka.actor._
import reactivemongo.bson.BSONObjectID
import org.joda.time.DateTime

import models._
import dao._
import tools.Conf

case class TrackState(
  track: Track,
  races: Seq[Race]
) {
  def raceLeaderboard(raceId: BSONObjectID): Seq[PlayerTally] = {
    races.find(_.id == raceId).map(_.leaderboard).getOrElse(Nil)
  }

  def playerRace(playerId: BSONObjectID): Option[Race] = {
    races.find(_.playerIds.contains(playerId)).orElse(races.headOption)
  }

  def withUpdatedRace(race: Race): TrackState = {
    races.indexWhere(_.id == race.id) match {
      case -1 => this
      case i => copy(races = races.updated(i, race))
    }
  }

  def escapePlayer(playerId: BSONObjectID): TrackState = {
    copy(
      races = races.map(_.removePlayerId(playerId))
    )
  }
}

case object RotateNextRace

class TrackActor(track: Track) extends Actor with ManageWind {

  val id = BSONObjectID.generate
  val course = track.course

  var state = TrackState(
    track = track,
    races = Nil
  )

  val players = scala.collection.mutable.Map[BSONObjectID, PlayerContext]()
  val paths = scala.collection.mutable.Map[BSONObjectID, RunPath]()

  def clock: Long = DateTime.now.getMillis

  val ticks = Seq(
    Akka.system.scheduler.schedule(1.second, 1.second, self, RotateNextRace),
    Akka.system.scheduler.schedule(0.seconds, course.gustGenerator.interval.seconds, self, SpawnGust),
    Akka.system.scheduler.schedule(0.seconds, Conf.frameMillis.milliseconds, self, FrameTick)
  )

  def receive = {

    /**
     * player join => added to context Map
     */
    case PlayerJoin(player) => {
      players += player.id -> PlayerContext(player, KeyboardInput.initial, OpponentState.initial, sender())
    }

    /**
     * player quit => removed from context Map
     */
    case PlayerQuit(player) => {
      players -= player.id
      paths -= player.id
    }

    /**
     * game heartbeat:
     * update wind (origin, speed and gusts positions)
     */
    case FrameTick => {
      updateWind()
    }

    /**
     * player update coming from websocket through player actor
     * context is updated, race started if requested
     */
    case PlayerUpdate(player, PlayerInput(opState, input, clientTime)) => {

      players.get(player.id).foreach { context =>
        val newContext = context.copy(input = input, state = opState)
        players += (player.id -> newContext)

        state.playerRace(player.id).foreach { race =>
          if (race.startTime.plusMinutes(10).isAfterNow) { // max 10min de trace
            paths += player.id -> TrackActor.tracePlayer(newContext, race, paths.toMap, clock)
          }
        }

        if (input.startCountdown) {
          state = TrackActor.startCountdown(state, byPlayerId = player.id)
        }

        if (input.escapeRace) {
          state = state.escapePlayer(player.id)
          paths -= player.id
        }

        if (context.state.crossedGates != newContext.state.crossedGates) {
          state = TrackActor.gateCrossedUpdate(state, newContext, players.toMap)
          state.playerRace(player.id).foreach { race =>
            TrackActor.saveIfFinished(track, race, newContext, paths.lift(newContext.player.id))
          }
        }


        context.ref ! raceUpdateForPlayer(player, clientTime)
      }
    }

    case msg: Message => {
      players.foreach { case (_, ctx) =>
        ctx.ref ! msg
      }
    }

    /**
     * new gust
     */
    case SpawnGust => generateGust()

    case RotateNextRace => {
      state = TrackActor.cleanStaleRaces(state)
    }

    case GetStatus => {
      sender ! (state.races, players.values.map(_.asOpponent))
    }
  }

  def playerOpponents(playerId: BSONObjectID): Seq[Opponent] = {
    players.toSeq.filterNot(_._1 == playerId).map(_._2.asOpponent)
  }

  def playerLeaderboard(playerId: BSONObjectID): Seq[PlayerTally] = {
    state.playerRace(playerId).map(_.id).map(state.raceLeaderboard).getOrElse(Nil)
  }

  def raceUpdateForPlayer(player: Player, clientTime: Long) = {
    RaceUpdate(
      serverNow = DateTime.now,
      startTime = TrackActor.playerStartTime(state, player),
      wind = wind,
      opponents = playerOpponents(player.id),
      leaderboard = playerLeaderboard(player.id),
      clientTime = clientTime
    )
  }

  override def postStop() = {
    ticks.foreach(_.cancel())
  }
}

object TrackActor {
  def props(track: Track) = Props(new TrackActor(track))

  def tracePlayer(ctx: PlayerContext, race: Race, paths: Map[BSONObjectID, RunPath], clock: Long): RunPath = {
    val playerId = ctx.player.id
    val elapsedMillis = clock - race.startTime.getMillis
    val currentSecond = elapsedMillis / 1000

    val p = PathPoint((elapsedMillis % 1000).toInt, ctx.state.position, ctx.state.heading)

    paths.lift(playerId) match {
      case Some(path) => {
        path.addPoint(currentSecond, p)
      }
      case None => {
        RunPath.init(currentSecond, p)
      }
    }
  }

  def cleanStaleRaces(state: TrackState): TrackState = {
    state.copy(
      races = state.races.filterNot { r =>
        raceIsClosed(r, state.track) && r.playerIds.isEmpty
      }
    )
  }

  def playerStartTime(state: TrackState, player: Player): Option[DateTime] = {
    state.playerRace(player.id).map(_.startTime)
  }

  def gateCrossedUpdate(state: TrackState, context: PlayerContext, players: Map[BSONObjectID, PlayerContext]): TrackState = {
    state.playerRace(context.player.id).map { race =>

      val playerIds =
        if (context.state.crossedGates.length == 1) race.playerIds :+ context.player.id
        else race.playerIds

      val leaderboard = playerIds.flatMap(players.get).map { context =>
        PlayerTally(context.player.id, context.player.handleOpt, context.state.crossedGates)
      }.sortBy { pt =>
        (-pt.gates.length, pt.gates.headOption)
      }

      val updatedRace = race.copy(playerIds = playerIds, leaderboard = leaderboard)

      state.withUpdatedRace(updatedRace)

    }.getOrElse(state)
  }

  def startCountdown(state: TrackState, byPlayerId: BSONObjectID): TrackState = {
    state.races.headOption match {
      case Some(race) if !raceIsClosed(race, state.track) => {
        state
      }
      case _ => {
        val newRace = Race(
          _id = BSONObjectID.generate,
          trackId = state.track.id,
          startTime = DateTime.now.plusSeconds(state.track.countdown),
          playerIds = Nil,
          leaderboard = Nil
        )
        state.copy(races = newRace +: state.races)
      }
    }
  }

  def raceIsClosed(race: Race, track: Track): Boolean =
    race.startTime.plusSeconds(track.countdown).isBeforeNow


  def saveIfFinished(track: Track, race: Race, ctx: PlayerContext, pathMaybe: Option[RunPath]): Unit = {
    if (ctx.state.crossedGates.length == track.course.laps * 2 + 1) {
      val runId = pathMaybe.map(_.runId).getOrElse(BSONObjectID.generate)
      val run = Run(
        _id = runId,
        trackId = track.id,
        raceId = race.id,
        playerId = ctx.player.id,
        playerHandle = ctx.player.handleOpt,
        startTime = race.startTime,
        tally = ctx.state.crossedGates,
        finishTime = ctx.state.crossedGates.head
      )
      for {
        _ <- pathMaybe.map(savePathIfBest(track.id, ctx.player.id)).getOrElse(Future.successful(()))
        _ <- RunDAO.save(run)
      }
      yield ()
    }
  }

  def savePathIfBest(trackId: BSONObjectID, playerId: BSONObjectID)(path: RunPath): Future[Unit] = {
    for {
      bestMaybe <- RunDAO.findBestOnTrackForPlayer(trackId, playerId)
      _ <- bestMaybe.map(_.id).map(RunPathDAO.deleteByRunId).getOrElse(Future.successful(()))
      _ <- RunPathDAO.save(path)
    }
    yield ()
  }
}
