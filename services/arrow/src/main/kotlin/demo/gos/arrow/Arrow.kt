package demo.gos.arrow

import demo.gos.common.*
import demo.gos.common.maths.Segment
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.eclipse.microprofile.reactive.messaging.OnOverflow
import java.security.SecureRandom
import java.util.*
import java.util.logging.Logger
import javax.inject.Inject
import javax.ws.rs.Consumes
import javax.ws.rs.POST
import javax.ws.rs.Path
import javax.ws.rs.core.MediaType


@Path("arrow")
class Arrow {
    companion object {
        val LOG: Logger = Logger.getLogger(Arrow::class.java.name)
    }
    val rnd = SecureRandom()

    @ConfigProperty(name = "runtime")
    lateinit var runtime: Optional<String>

    @Inject
    @Channel("display")
    @OnOverflow(OnOverflow.Strategy.DROP)
    lateinit var displayEmitter: Emitter<JsonArray>

    @Inject
    @Channel("kill-single")
    @OnOverflow(OnOverflow.Strategy.DROP)
    lateinit var killSingleEmitter: Emitter<JsonObject>

    @Path("/fire")
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    fun fire(fire: Fire) {
        // Is in range?
        val segToDest = Segment(fire.source.toPoint(), fire.target.toPoint())
        val shootSize = segToDest.size()
        if (shootSize < fire.range) {
            if (rnd.nextDouble() < fire.accuracy) {
                killSingleEmitter.send(JsonObject().put("id", fire.target.id))
            }
            display(fire.source, fire.target)
        } else {
            // not in range
            val dest = segToDest.derivate().normalize().mult(fire.range).add(fire.source.toPoint())
            display(fire.source, Noise(fire.target.id, dest.x(), dest.y()))
        }
    }

    private fun display(source: Noise, target: Noise) {
        val data = DisplayData(
                id = "ARROW-${runtime.orElse("u")}-${Players.randomName(rnd)}",
                x = source.x,
                y = source.y,
                sprite = "arrow",
                tween = Tween(
                        x = target.x,
                        y = target.y,
                        time = 300.0
                )
        )
        val json = JsonArray(listOf(data))
        displayEmitter.send(json)
    }

}
