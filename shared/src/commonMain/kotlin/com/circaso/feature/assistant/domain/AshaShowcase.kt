package com.circaso.feature.assistant.domain

/**
 * Debug builds only: a tour of the mascot (both looks, every scene, the moods, the activities and some one-shots) so a
 * build on a phone shows all of it without waiting for the right conversation. Long-press the stage in voice mode.
 */
object AshaShowcase {
    data class Step(val state: AshaAvatarState, val reaction: AshaReaction? = null)

    private fun s(look: AshaLook, scene: AshaScene, mood: AshaMood, activity: AshaActivity, reaction: AshaReaction? = null, voice: Float = 0f) =
        Step(AshaAvatarState(look, scene, mood, activity, voice), reaction)

    val steps: List<Step> = run {
        val a = AshaLook.Asha
        val r = AshaLook.Robbie
        listOf(
            s(a, AshaScene.None, AshaMood.Calm, AshaActivity.Idle),
            s(r, AshaScene.None, AshaMood.Happy, AshaActivity.Idle, AshaReaction.Thanks),
            s(a, AshaScene.Beach, AshaMood.Sassy, AshaActivity.Sipping),
            s(a, AshaScene.Beach, AshaMood.Calm, AshaActivity.ReadingBook),
            s(r, AshaScene.Beach, AshaMood.Excited, AshaActivity.BuildingSandcastle),
            s(r, AshaScene.BeachSunset, AshaMood.Calm, AshaActivity.WatchingSunset),
            s(a, AshaScene.Gym, AshaMood.Proud, AshaActivity.Yoga),
            s(r, AshaScene.Gym, AshaMood.Excited, AshaActivity.Lifting),
            s(r, AshaScene.Gym, AshaMood.Proud, AshaActivity.Flexing),
            s(r, AshaScene.Running, AshaMood.Happy, AshaActivity.Jogging),
            s(a, AshaScene.Office, AshaMood.Focused, AshaActivity.ReadingTransactions),
            s(a, AshaScene.Office, AshaMood.Calm, AshaActivity.Sipping),
            s(r, AshaScene.Trading, AshaMood.Focused, AshaActivity.MakingChart),
            s(r, AshaScene.Crypto, AshaMood.Excited, AshaActivity.Juggling),
            s(a, AshaScene.Crypto, AshaMood.Curious, AshaActivity.ExaminingCoin),
            s(a, AshaScene.Saving, AshaMood.Proud, AshaActivity.Idle, AshaReaction.MoneyReceived),
            s(r, AshaScene.Home, AshaMood.Calm, AshaActivity.Sipping),
            s(a, AshaScene.School, AshaMood.Thinking, AshaActivity.Thinking),
            s(a, AshaScene.Shopping, AshaMood.Sassy, AshaActivity.CheckingReceipt),
            s(r, AshaScene.Shopping, AshaMood.Happy, AshaActivity.CarryingBags),
            s(a, AshaScene.Driving, AshaMood.Happy, AshaActivity.Singing, voice = 0.7f),
            s(r, AshaScene.Driving, AshaMood.Calm, AshaActivity.Driving),
            s(a, AshaScene.Support, AshaMood.Caring, AshaActivity.Listening),
            s(a, AshaScene.None, AshaMood.Concerned, AshaActivity.WritingStatement, AshaReaction.PaymentFailed),
            s(r, AshaScene.None, AshaMood.Apologetic, AshaActivity.SearchingWeb),
            s(a, AshaScene.Celebration, AshaMood.Excited, AshaActivity.Done, AshaReaction.GoalReached),
            s(r, AshaScene.Celebration, AshaMood.Excited, AshaActivity.Done, AshaReaction.GoalReached),
            s(a, AshaScene.None, AshaMood.Sleepy, AshaActivity.Idle, AshaReaction.IdleQuirk),
            s(r, AshaScene.None, AshaMood.Calm, AshaActivity.Idle, AshaReaction.IdleQuirk),
        )
    }
}
