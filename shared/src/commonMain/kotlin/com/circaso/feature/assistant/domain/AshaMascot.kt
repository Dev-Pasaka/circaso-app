package com.circaso.feature.assistant.domain

import kotlinx.coroutines.flow.Flow

/*
 * Asha's animated mascot (asha-next.md part B, asha-mascot-personality.md). The Rive file `asha.riv` has a view model
 * `Asha` whose enum properties take exactly the [rive] strings below. Everything here is plain Kotlin so the rules
 * (look from the voice, the sass rules, offline) are unit-tested without Rive.
 */

/** Feminine look (Asha) for a female voice, masculine look (Robbie) for a male voice. */
enum class AshaLook(val rive: String) {
    Asha("asha"),
    Robbie("robbie");

    companion object {
        /**
         * The look for a voice's `gender` from A12 (`GET /v1/assistant/voices`). Only an explicit male voice gets
         * Robbie; anything else (female, neutral, unknown, missing) keeps Asha, the assistant's own name.
         */
        fun forVoiceGender(gender: String?): AshaLook = when (gender?.trim()?.lowercase()) {
            "male", "m", "man", "masculine" -> Robbie
            else -> Asha
        }
    }
}

/** Where she is. The server picks it from the topic; it changes only on a clear, sustained topic change. */
enum class AshaScene(val rive: String) {
    None("none"), Beach("beach"), BeachSunset("beach_sunset"), Gym("gym"), Office("office"), Trading("trading"),
    Driving("driving"), Saving("saving"), Support("support"), Celebration("celebration"), Running("running"),
    Shopping("shopping"), Home("home"), School("school"), Crypto("crypto");

    companion object {
        /** Null for a value this build doesn't know (a newer server), so the caller keeps what it had. */
        fun parse(value: String?): AshaScene? = entries.firstOrNull { it.rive == value?.trim()?.lowercase() }
    }
}

/** Her expression, held while it lasts. */
enum class AshaMood(val rive: String) {
    Calm("calm"), Happy("happy"), Excited("excited"), Concerned("concerned"), Proud("proud"), Thinking("thinking"),
    Sassy("sassy"), Curious("curious"), Focused("focused"), Caring("caring"), Sleepy("sleepy"), Apologetic("apologetic");

    companion object {
        fun parse(value: String?): AshaMood? = entries.firstOrNull { it.rive == value?.trim()?.lowercase() }
    }
}

/** What she is doing right now. */
enum class AshaActivity(val rive: String) {
    Idle("idle"), Listening("listening"), Thinking("thinking"), SearchingWeb("searching_web"),
    ReadingTransactions("reading_transactions"), MakingChart("making_chart"), WritingStatement("writing_statement"),
    Speaking("speaking"), Done("done"), ReadingBook("reading_book"), Sipping("sipping"), WatchingSunset("watching_sunset"),
    Lifting("lifting"), Driving("driving"), Singing("singing"), Jogging("jogging"), CarryingBags("carrying_bags"),
    Yoga("yoga"), Flexing("flexing"), Juggling("juggling"), ExaminingCoin("examining_coin"),
    CheckingReceipt("checking_receipt"), BuildingSandcastle("building_sandcastle");

    companion object {
        fun parse(value: String?): AshaActivity? = entries.firstOrNull { it.rive == value?.trim()?.lowercase() }
    }
}

/** One-shot animations: set `reaction`, fire `react`; it plays once (1–3 s) and returns to the mood. */
enum class AshaReaction(val rive: String) {
    MoneyReceived("money_received"), PaymentSent("payment_sent"), PaymentFailed("payment_failed"), Thanks("thanks"),
    GoalReached("goal_reached"), Poke("poke"), IdleQuirk("idle_quirk");

    companion object {
        fun parse(value: String?): AshaReaction? = entries.firstOrNull { it.rive == value?.trim()?.lowercase() }
    }
}

/**
 * How much sass the mascot may show (asha-mascot-personality.md, "Sass rules"). The setting itself is not built yet
 * (it will live in Asha's settings); until then everyone gets [Playful], the designed default.
 */
enum class SassLevel { Calm, Playful, Sassy }

/**
 * The optional tags the server adds to Asha's status events (asha-next.md part B), all nullable: a field the server
 * didn't send, or sent with a value this build doesn't know, is null and leaves the previous value in place.
 * [sensitive] is the backend's "this turn is about hardship" mark; the backend doesn't send it yet (2026-11-04), so it
 * is false unless present.
 */
data class AshaTags(
    val scene: AshaScene? = null,
    val mood: AshaMood? = null,
    val activity: AshaActivity? = null,
    val reaction: AshaReaction? = null,
    val sensitive: Boolean = false,
) {
    val isEmpty get() = scene == null && mood == null && activity == null && reaction == null && !sensitive
}

/**
 * What the conversation has said so far: the latest scene, mood and activity the server sent, held until a newer one.
 * [moodSensitive] remembers whether the turn that set the mood was sensitive, so a later frame of the same answer
 * can't make a sassy mood acceptable.
 */
data class AshaMascotTags(
    val scene: AshaScene? = null,
    val mood: AshaMood? = null,
    val activity: AshaActivity? = null,
    val moodSensitive: Boolean = false,
) {
    /** Folds one status event's tags in. Reactions are one-shots and are not held. */
    fun merge(next: AshaTags?): AshaMascotTags {
        if (next == null) return this
        return AshaMascotTags(
            scene = next.scene ?: scene,
            mood = next.mood ?: mood,
            activity = next.activity ?: activity,
            moodSensitive = if (next.mood != null) next.sensitive else moodSensitive || next.sensitive,
        )
    }

    /** At the end of an answer her work is done: the activity is released, scene and mood stay. */
    fun turnDone() = copy(activity = null)
}

/** Everything the mascot shows at one moment; [voiceLevel] 0..1 moves her mouth while she speaks. */
data class AshaAvatarState(
    val look: AshaLook = AshaLook.Asha,
    val scene: AshaScene = AshaScene.None,
    val mood: AshaMood = AshaMood.Calm,
    val activity: AshaActivity = AshaActivity.Idle,
    val voiceLevel: Float = 0f,
) {
    companion object {
        /** Offline: no scene, calm, idle. */
        val Offline = AshaAvatarState()
    }
}

/** Which conversation the mascot is in, for the rules that depend on it. */
data class AshaMascotContext(
    val online: Boolean,
    /** A support conversation (asha-next.md part C): no sass at all. No such conversation exists yet. */
    val support: Boolean = false,
    val sass: SassLevel = SassLevel.Playful,
    /** After 23:00 and before 05:00 on the phone (asha-mascot-personality.md: late night → sleepy). */
    val lateNight: Boolean = false,
)

object AshaMascotRules {
    /**
     * The state to show. [appActivity] is what the screen itself knows (listening, speaking, thinking while waiting);
     * it wins over the server's activity except while Asha is working on an answer ([AshaActivity.Thinking]), when a
     * more specific server activity (reading_transactions, making_chart, …) is shown instead.
     */
    fun resolve(
        look: AshaLook,
        tags: AshaMascotTags,
        context: AshaMascotContext,
        appActivity: AshaActivity? = null,
        voiceLevel: Float = 0f,
    ): AshaAvatarState {
        if (!context.online) return AshaAvatarState.Offline.copy(look = look)
        val activity = when (appActivity) {
            null -> tags.activity ?: AshaActivity.Idle
            AshaActivity.Thinking -> tags.activity?.takeIf { it != AshaActivity.Idle } ?: AshaActivity.Thinking
            else -> appActivity
        }
        return AshaAvatarState(
            look = look,
            scene = tags.scene ?: AshaScene.None,
            mood = moodFor(tags.mood ?: AshaMood.Calm, sensitive = tags.moodSensitive, context = context),
            activity = activity,
            voiceLevel = if (activity == AshaActivity.Speaking) voiceLevel.coerceIn(0f, 1f) else 0f,
        )
    }

    /**
     * The sass rules: `sassy` becomes `calm` when the setting is Calm, in support conversations, and on turns the
     * backend marks sensitive (hardship: low balance, failed payments, debt, fees, bereavement, medical bills).
     */
    fun moodFor(mood: AshaMood, sensitive: Boolean, context: AshaMascotContext): AshaMood = when {
        mood == AshaMood.Sassy && (context.sass == SassLevel.Calm || context.support || sensitive) -> AshaMood.Calm
        // Late at night a calm Asha is sleepy; never on a hard turn or in support, where she stays attentive.
        mood == AshaMood.Calm && context.lateNight && !sensitive && !context.support -> AshaMood.Sleepy
        else -> mood
    }

    /** Late night on the phone: 23:00 to 04:59 local. */
    fun isLateNight(localHour: Int): Boolean = localHour >= 23 || localHour < 5

    /** Offline she stays still and idle, so one-shots are dropped; a poke (the person's own tap) still answers. */
    fun allowReaction(reaction: AshaReaction, context: AshaMascotContext): Boolean = context.online || reaction == AshaReaction.Poke
}

/** The look to show, from the voice the person chose (or the server's default voice), kept on the phone for offline. */
interface AshaLookSource {
    val look: Flow<AshaLook>

    /** Re-reads the voices once (A12) to learn the chosen voice's gender; quiet when offline. */
    suspend fun refresh()
}
