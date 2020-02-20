package demo.gos.hero

import demo.gos.common.DisplayData
import demo.gos.common.Fire
import demo.gos.common.Noise
import demo.gos.common.maths.Point
import java.util.*
import java.util.logging.Logger

class Bow(private val arrowService: Arrow) {
    companion object {
        val LOG: Logger = Logger.getLogger(Bow::class.java.name)
    }

    private val id = "BOW-Q-" + UUID.randomUUID().toString()

    fun getDisplayData(heroPos: Point): DisplayData {
        val pos = getBowPosition(heroPos)
        return DisplayData(
                id = id,
                x = pos.x(),
                y = pos.y(),
                sprite = "bow"
        )
    }

    fun fire(heroPos: Point, target: Noise, burst: Long, accuracy: Double, range: Double) {
        kotlin.runCatching {
            for (i in 0..burst) {
                arrowService.fire(Fire(Noise.fromPoint(id, getBowPosition(heroPos)), target, accuracy, range))
            }
        }.onFailure {
            LOG.warning("Error while firing arrow: ${it.message}")
        }
    }

    private fun getBowPosition(heroPos: Point): Point = heroPos.add(Point(-3.0, 0.5))
}
