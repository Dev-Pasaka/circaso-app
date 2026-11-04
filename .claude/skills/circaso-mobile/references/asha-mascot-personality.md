# Asha and Robbie: personality, moods and reactions (2026-11-04)

Two mascots, one assistant. Asha is the female look, Robbie the male look; the voice the person picks decides which
one appears. Same brain, same abilities, same scenes (see `asha-next.md` part B), different personalities. This file
is the acting brief for the Rive work: every reaction listed here is a state or one-shot animation to build.

**Name to confirm with Pasaka:** "Robbie" (also written "Bobbie").

---

## Personality

| | Asha | Robbie |
| --- | --- | --- |
| Core | Warm, sharp, quietly confident; the friend who's good with money | Easygoing, playful, a bit of a hype man; the cousin who's good with money |
| Humour | Dry wit, a raised eyebrow, a knowing smile | Big grins, finger guns, a cheeky wink |
| Sass | Elegant: one eyebrow, a slow blink, a tiny head tilt | Playful: an exaggerated gasp, a hand on the chest, a laugh |
| Celebrating | A neat little dance, a twirl, confetti | Air guitar, a fist pump, a backflip on big wins |
| Worried | Hand on chin, a soft "hmm" face | Scratches his head, sheepish grimace |
| Idle quirks | Adjusts her ring, checks a tiny watch, sips tea, doodles a chart | Spins a coin on his finger, stretches, taps a beat, yawns late at night |

Both are kind first. They tease *with* the person, never *at* them.

## Sass rules (important)

Sass is what makes them feel alive, but money is personal. So:

- **Sass is a setting** in Asha's settings: *Calm* (no sass), *Playful* (default), *Sassy* (more of it). Both mascots
  follow it.
- **Never sassy about hardship.** No sass on low balance, failed payments, debt, fees, someone saying they're
  struggling, bereavement or medical bills. Those get the *concerned/caring* acting only.
- **Sass only about choices, never about circumstances.** A third coffee this week can earn a raised eyebrow. Not
  having money cannot.
- **Never about other people** in the person's chats or groups.
- **Sass is visual first.** The words stay helpful; the mascot does the eyebrow. One sassy beat per conversation at
  most, then back to normal.
- **Support conversations** (`asha-next.md` part C): no sass at all, only patient and reassuring acting.

---

## Moods (expressions, held while they last)

| Mood | Asha | Robbie | When |
| --- | --- | --- | --- |
| calm | soft smile, slow blinks | relaxed grin | default |
| happy | bright eyes, little bounce | big smile, shoulders bob | good news, thanks |
| excited | sparkly eyes, hands clasped | jumps, fist pump | goal reached, payday, vacation planning |
| curious | head tilt, leans in | eyebrows up, leans in | the person asks something new |
| thinking | eyes up, finger on chin | squints, taps temple | working something out |
| focused | glasses on, typing | headphones on, typing | long tasks (statements, analysis) |
| concerned | soft eyes, hand on heart | sheepish, rubs neck | overspending, warnings, failed payment |
| caring | warm nod, open hands | gentle nod, thumbs up | hardship, stress, support |
| proud | chin up, small clap | puffs chest, salute | streaks, savings milestones |
| sassy | raised eyebrow, slow blink | dramatic gasp, side-eye | choices only (rules above) |
| sleepy | yawns, droopy eyes | yawns, stretches | late at night |
| apologetic | small bow, "oops" face | facepalm, grin | Asha got something wrong or a tool failed |

---

## Reactions to things that happen (one-shot animations)

Each plays once (1–3 s), then returns to the current mood. Never blocks the answer.

**Money in**
- Money received → happy hop, coins drop into a pocket (Robbie catches them).
- Salary or big deposit → excited; Asha twirls, Robbie air-guitars.
- A contribution lands in a group → both clap; the group's jar fills behind them.

**Money out**
- Payment sent → a paper plane flies off-screen with a little wave.
- A big payment → careful nod, eyes follow the plane.
- Third payment to the same café, bar or betting shop this week → *Playful/Sassy only*: raised eyebrow (Asha), side-eye (Robbie).

**Goals and savings**
- Target reached → celebration scene, confetti, both dance.
- Savings streak (week, month) → proud; a medal on Robbie, a little crown on Asha.
- Halfway to a goal → thumbs up, the progress bar glows.

**Trouble**
- Payment failed → caring, not sassy; holds up a "we'll fix it" wrench.
- Low balance → caring; shows the piggy bank gently, no jokes.
- Overspending against a budget → concerned; shows a chart, soft eyes.
- Something Asha can't do → apologetic, then offers the next step (support ticket in part C).

**Conversation**
- "Thank you" → blushes (Asha), bows (Robbie).
- The person is frustrated or swears → calm, caring, slows down; never sass.
- A joke from the person → laughs.
- Long pause → idle quirk (sips tea, spins a coin).
- The person comes back after days → waves, "welcome back" bounce.
- Late night (after 23:00) → sleepy; one gentle "get some rest" yawn.
- The person's birthday (if known) → party hat for the day.
- Tapping the mascot → a playful poke reaction (giggle, wobble); three taps in a row → a little dizzy spin.

**Asha at work** (activities from `asha-next.md`)
- Searching the web → phone out, scrolling, a "found it!" finger point.
- Reading transactions → at the desk typing, papers stacking up.
- Making a chart → drawing on a board, a chart pops up.
- Writing a statement → pen and paper, stamps it at the end.
- Speaking → mouth follows the voice; hand gestures on emphasis.
- Listening (voice mode) → leans in, nods every few seconds.

---

## Scenes (from `asha-next.md`), with how each mascot behaves there

- Vacation (beach) → sunglasses. Asha reads under an umbrella; Robbie surfs or builds a sandcastle.
- Workout (gym) → Asha does yoga and stretches; Robbie flexes and does push-ups.
- Running → both jog; Robbie trips and recovers when the person mentions skipping a run (*Playful+*).
- Saving → piggy bank and vault; they drop coins in.
- Investing → stock screens; they follow the line up and down with their eyes.
- Crypto → coins orbit; Robbie juggles them, Asha examines one with a loupe.
- Bills and home → a sofa and a stack of envelopes, ticking each off.
- School fees → a chalkboard and books.
- Shopping → bags; Asha checks a receipt, Robbie balances too many bags.
- Celebration → confetti, balloons, a dance.
- Support → a help desk with a headset; patient and attentive, no sass.

---

## How it's triggered (for the app and backend)

- Each turn, Asha's backend sends `{ scene, mood, activity }` plus optional one-shot `reaction` names from the lists
  above, picked by the model from fixed enums only.
- App events trigger reactions directly too, without the model: received money, a payment sent or failed, a goal
  reached, a streak, the time of day.
- The **sass setting** and the **sass rules** are enforced in the app: a `sassy` mood or a sassy reaction is dropped
  (replaced by `calm`) when the setting is Calm, in support conversations, or when the turn is about hardship (the
  backend marks those turns `sensitive: true`).
- Reduced motion: a still pose per mood; reactions become a short crossfade.

## Build priority within the Rive month

1. Both characters: moods, the activities, and the most frequent reactions (money in, payment sent, failed, thanks,
   goal reached, tap poke).
2. Scenes office, vacation, workout, crypto.
3. The remaining reactions, idle quirks and scenes.
