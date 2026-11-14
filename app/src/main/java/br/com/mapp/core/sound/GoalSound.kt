package br.com.mapp.core.sound

/**
 * Som da meta do dia batida, escolhido nos ajustes do botão "M". [CASH] é o "cha-ching" do
 * [CashSound]; os outros reaproveitam os sons gerados do [SoundSynth] ([great]).
 */
enum class GoalSound(val id: String, val label: String, val great: GreatSound?) {
    CASH("cash", "Dinheiro", null),
    CHIME("chime", "Sino", GreatSound.CHIME),
    DING_DONG("dingdong", "Ding-dong", GreatSound.DING_DONG),
    RISE("rise", "Subida", GreatSound.RISE),
    DOUBLE_BEEP("beep2", "Bip duplo", GreatSound.DOUBLE_BEEP),
    OFF("off", "Sem som", null);

    companion object {
        val DEFAULT = CASH

        fun fromId(id: String?): GoalSound = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
