package demo.gos.hero

import demo.gos.common.*
import demo.gos.common.maths.Point
import demo.gos.common.maths.Segment
import io.quarkus.runtime.ShutdownEvent
import io.quarkus.runtime.StartupEvent
import io.reactivex.Flowable
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import org.eclipse.microprofile.config.inject.ConfigProperty
import org.eclipse.microprofile.reactive.messaging.Channel
import org.eclipse.microprofile.reactive.messaging.Emitter
import org.eclipse.microprofile.reactive.messaging.Incoming
import org.eclipse.microprofile.reactive.messaging.OnOverflow
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import javax.enterprise.event.Observes
import javax.enterprise.inject.spi.CDI
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import kotlin.concurrent.schedule
import kotlin.concurrent.scheduleAtFixedRate

@Singleton
class Hero {
    companion object {
        val LOG: Logger = Logger.getLogger(Hero::class.java.name)
        const val DELTA_MS = 1000L
        val HEROES = listOf(
                "aria-stark", "bran-stark", "catelyn-stark", "cersei-lannister", "daenerys-targaryen", "davos-seaworth", "gendry", "jaime-lannister", "jeor-mormont", "jon-snow", "kal-drogo", "lady-brynne", "little-finger", "margeary-tyrell", "melisandre", "ned-stark", "prince-joffrey", "rickon-stark", "robb-stark", "robert-barratheon", "sansa-stark", "stannis-barratheon", "the-dog", "theon-greyjoy", "tyrion-lannister", "tywin-lannister", "varys", "ygritte"
        )
    }

    val timer = Timer()
    private val rnd = SecureRandom()

    @ConfigProperty(name = "X")
    lateinit var X: Optional<Double>

    @ConfigProperty(name = "runtime")
    lateinit var runtime: Optional<String>

    @ConfigProperty(name = "Y")
    lateinit var Y: Optional<Double>

    @ConfigProperty(name = "name")
    lateinit var configName: Optional<String>

    @ConfigProperty(name = "use-bow", defaultValue = "false")
    lateinit var useBow: Provider<Boolean>

    @ConfigProperty(name = "accuracy", defaultValue = "0.5")
    lateinit var accuracy: Provider<Double>

    @ConfigProperty(name = "range", defaultValue = "400.0")
    lateinit var range: Provider<Double>

    @ConfigProperty(name = "bow-range", defaultValue = "1024.0")
    lateinit var bowRange: Provider<Double>

    @ConfigProperty(name = "bow-accuracy", defaultValue = "0.2")
    lateinit var bowAccuracy: Provider<Double>

    @ConfigProperty(name = "bow-burst", defaultValue = "1")
    lateinit var bowBurst: Provider<Long>

    @ConfigProperty(name = "speed", defaultValue = "70.0")
    lateinit var speed: Provider<Double>

    private val id = AtomicReference<String>()
    private val name = AtomicReference<String>()

    private val randomDest = AtomicReference<Point>()
    private val dead = AtomicBoolean(false)

    private val target = AtomicReference<PerceivedNoise>()
    private val targetCatapult = AtomicReference<PerceivedNoise>()
    private val bow = AtomicReference<Bow>()

    val initialized = AtomicBoolean(false)
    val position = AtomicReference<Point>()
    val ended = AtomicBoolean(false)

    @Inject
    @Channel("hero-making-noise")
    lateinit var heroNoiseEmitter: Emitter<JsonObject>

    @Inject
    @Channel("display")
    lateinit var displayEmitter: Emitter<JsonArray>

    @Inject
    @Channel("load-weapon")
    @OnOverflow(OnOverflow.Strategy.BUFFER)
    lateinit var loadWeaponEmitter: Emitter<JsonObject>

    @Inject
    @Channel("weapon-making-noise")
    @OnOverflow(OnOverflow.Strategy.BUFFER)
    lateinit var weaponMakingNoiseFlowable: Flowable<JsonObject>

    @Inject
    @Channel("villain-making-noise")
    @OnOverflow(OnOverflow.Strategy.BUFFER)
    lateinit var villainMakingNoiseFlowable: Flowable<JsonObject>

    fun onStart(@Observes e: StartupEvent) {
        init()
        LOG.info("$id has joined the game (${position.get().x()}, ${position.get().y()}) ${if(useBow.get()) "with a bow" else ""}")
        initialized.set(true)
        timer.scheduleAtFixedRate(DELTA_MS, DELTA_MS) {
            scheduled()
        }
        if (useBow.get()) {
            villainMakingNoiseFlowable
                    .subscribe { listenToVillains(it.mapTo(Noise::class.java)) }
            bow.set(CDI.current().select(Bow::class.java).get())
            timer.schedule(DELTA_MS) {
                // Move to the weapon area
                val pos = position.get()
                position.set(GameObjects.startingPoint(rnd, Areas.spawnHeroesArea, null, pos.y()))
            }
        } else {
            weaponMakingNoiseFlowable
                    .takeUntil { targetCatapult.get() != null }
                    .subscribe { weaponMakingNoise(it) }
        }
    }

    fun onShutdown(@Observes e: ShutdownEvent) {
        timer.cancel()
    }

    fun scheduled() {
        if (!dead.get()) {
            makeNoise()
            val catapult = targetCatapult.get()?.noise
            if (catapult != null) {
                if (isOn(catapult)) {
                    loadCatapult(catapult)
                } else {
                    walkTo(catapult)
                }
            } else if (useBow.get() && !ended.get()) {
                walkToRange()
                val b = bow.get()
                val t = target.get()
                if (b != null && t != null) {
                    b.fire(position.get(), t.noise, bowBurst.get(), bowAccuracy.get(), bowRange.get())
                }
            } else {
                walkRandom()
            }
        }
        display()
    }

    private fun loadCatapult(catapult: Noise) {
        kotlin.runCatching {
            loadWeaponEmitter.send(JsonObject().put("id", catapult.id).put("val", 1))
        }.onFailure {
            LOG.warning("Error while loading weapon: ${it.message}")
        }
    }

    private fun walkTo(noise: Noise) {
        position.set(Players.walk(
                rnd = rnd,
                pos = position.get(),
                dest = noise.toPoint(),
                accuracy = accuracy.get(),
                speed = speed.get(),
                delta = DELTA_MS.toDouble() / 1000
        ))

        LOG.finest("$id at ${position.get()} walking toward $noise")
    }

    private fun walkRandom() {
        val positions = Players.walkRandom(
                rnd = rnd,
                pos = position.get(),
                dest = randomDest.get(),
                accuracy = accuracy.get(),
                speed = speed.get(),
                delta = DELTA_MS.toDouble() / 1000
        )
        position.set(positions.first)
        randomDest.set(positions.second)

        LOG.finest("$id at ${position.get()} walking randomly")
    }

    private fun walkToRange() {
        val t = target.get()
        if (t != null) {
            val seg = Segment(position.get(), t.noise.toPoint())
            val dist = seg.size()
            if (dist > range.get()) {
                // Walk toward target
                position.set(Players.walk(
                        rnd = rnd,
                        pos = position.get(),
                        dest = t.noise.toPoint(),
                        accuracy = accuracy.get(),
                        speed = speed.get(),
                        delta = DELTA_MS.toDouble() / 1000
                ))
            } else if (dist < 200) {
                // Too close, move back!
                val dest = Areas.arena.fitInto(position.get().add(seg.derivate().mult(-1.0)))
                position.set(Players.walk(
                        rnd = rnd,
                        pos = position.get(),
                        dest = dest,
                        accuracy = accuracy.get(),
                        speed = speed.get(),
                        delta = DELTA_MS.toDouble() / 1000
                ))
            }
        }
    }

    private fun isOn(noise: Noise): Boolean {
        return Circle.fromCenter(position.get(), 20.0).contains(noise.toPoint())
    }

    private fun display() {
        val sprite = if (dead.get()) "rip" else name.get()
        val data = DisplayData(
                id = id.get(),
                x = position.get().x(),
                y = position.get().y(),
                sprite = sprite,
                label = id.get()
        )
        val json = JsonObject.mapFrom(data)
        val b = bow.get()
        if (b != null) {
            val jsonBow = JsonObject.mapFrom(b.getDisplayData(position.get()))
            displayEmitter.send(JsonArray(listOf(json, jsonBow)))
        } else {
            displayEmitter.send(JsonArray(listOf(data)))
        }
    }

    @Incoming("gameover")
    fun gameover(o: JsonObject) {
        if (!initialized.get()) {
            return
        }
        ended.set(true)
    }

    private fun init() {
        name.set(configName.orElse(HEROES[rnd.nextInt(HEROES.size)]))
        id.set("HERO-QUARKUS-${name.get()}-${runtime.orElse("u")}-${Players.randomName(rnd)}")
        dead.set(false)
        ended.set(false)
        position.set(GameObjects.startingPoint(rnd, Areas.spawnHeroesArea, null, Y.orElse(null)))
        targetCatapult.set(null)
        randomDest.set(null)
        target.set(null)
    }

    fun weaponMakingNoise(o: JsonObject) {
        if (!initialized.get()) {
            return
        }
        val noise = o.mapTo(Noise::class.java)
        LOG.finest("$id received weapon noise: $noise")
        val perceived = PerceivedNoise.create(noise, position.get())
        val currentTarget = targetCatapult.get()
        if (currentTarget == null) {
            // New target
            targetCatapult.set(perceived)
        } else if (!isOn(currentTarget.noise)) {
            if (perceived.isStrongerThan(currentTarget)) {
                targetCatapult.set(perceived)
            }
        }
    }

    @Incoming("kill-around")
    fun onKillAround(o: JsonObject) {
        if (!initialized.get()) {
            return
        }
        if (dead.get()) {
            return
        }
        val zone = o.mapTo(Circle::class.java)
        if (zone.contains(position.get())) {
            LOG.info("Uuuuuhhggg!!!! (Today, $id has died)")
            dead.set(true)
        }
    }

    @Incoming("kill-single")
    fun onKillSingle(o: JsonObject) {
        if (!initialized.get()) {
            return
        }
        if (dead.get()) {
            return
        }
        if (id.get() == o.getString("id")) {
            LOG.info("Uuuuuhhggg!!!! (Today, $id has died)")
            dead.set(true)
        }
    }

    private fun makeNoise() {
        heroNoiseEmitter.send(JsonObject.mapFrom(Noise.fromPoint(id.get(), position.get())))
        // Also decrease heard noises
        targetCatapult.get()?.fade()
        target.get()?.fade()
    }

    private fun listenToVillains(noise: Noise) {
        if (!initialized.get()) {
            return
        }
        val perceived = PerceivedNoise.create(noise, position.get())
        target.getAndUpdate {
            if (it == null) {
                return@getAndUpdate perceived
            } else {
                if (noise.id == it.noise.id) {
                    // Update target position
                    return@getAndUpdate perceived
                } else {
                    // 40% chances to get attention
                    if (perceived.isStrongerThan(it) && rnd.nextInt(100) < 40) {
                        return@getAndUpdate perceived
                    }
                    return@getAndUpdate it
                }
            }
        }
    }
}
