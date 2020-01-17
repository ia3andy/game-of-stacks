package demo.gos.catapult

import demo.gos.common.DisplayData
import demo.gos.common.GameCommand
import demo.gos.common.Noise
import demo.gos.common.VertxScheduler
import demo.gos.common.maths.Point
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import io.quarkus.scheduler.Scheduled
import io.smallrye.reactive.messaging.annotations.Channel
import io.smallrye.reactive.messaging.annotations.Emitter
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import kotlinx.coroutines.runBlocking
import org.eclipse.microprofile.reactive.messaging.Incoming
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import javax.enterprise.event.Observes
import javax.inject.Inject
import javax.inject.Singleton

val colorize = fun(gauge: Double): String {
  val red = (gauge * 255).toInt()
  return "rgb($red,0,128)"
}
// TODO: handle /load http handler

@Singleton
class CatapultQuarkus : BaseCatapult("CATA-Q-" + UUID.randomUUID().toString(), colorize) {
  companion object {
    const val DELTA_MS = 200L
  }

  @Inject
  lateinit var vertx: Vertx

  lateinit var vertxScheduler: VertxScheduler

  private val initialized = AtomicBoolean(false)

  @Inject
  @Channel("weapon-making-noise")
  lateinit var noiseEmitter: Emitter<JsonObject>

  @Inject
  @Channel("display")
  lateinit var displayEmitter: Emitter<JsonObject>

  @Inject
  @Channel("kill-around")
  lateinit var killAroundEmitter: Emitter<JsonObject>

  fun onStart(@Observes e: StartupEvent) {
    vertxScheduler = VertxScheduler(vertx)
    vertxScheduler.schedule(200, this::scheduled)
    initialized.set(true)
  }

  fun onShutdown(@Observes e: ShutdownEvent) {
    vertxScheduler.cancel()
  }

  fun scheduled() {
    runBlocking {
      update(DELTA_MS.toDouble() / 1000.0)
    }
  }

  @Incoming("game")
  fun game(o: JsonObject) {
    if(!initialized.get()) {
      return
    }
    onGameCommand(o.mapTo(GameCommand::class.java))
  }

  @Incoming("villain-making-noise")
  fun villainMakingNoise(o: JsonObject) {
    if(!initialized.get()) {
      return
    }
    listenToVillains(o.mapTo(Noise::class.java))
  }

  @Incoming("load-catapult")
  fun loadCatapult(o: JsonObject) {
    if(!initialized.get()) {
      return
    }
    if (o.getString("id") == id) {
      runBlocking {
        load {
          it(o.getDouble("val"))
        }
      }
    }
  }

  override suspend fun makeNoise(noise: Noise) {
    noiseEmitter.send(JsonObject.mapFrom(noise))
  }

  override fun createBoulder(pos: Point, dest: Point, speed: Double, impact: Double): BaseBoulder {
    return Boulder(displayEmitter, killAroundEmitter, pos, dest, speed, impact)
  }

  override suspend fun display(data: DisplayData) {
    displayEmitter.send(JsonObject.mapFrom(data))
  }
}
