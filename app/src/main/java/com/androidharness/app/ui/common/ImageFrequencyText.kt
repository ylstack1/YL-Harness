package com.androidharness.app.ui.common

import androidx.annotation.StringRes
import com.androidharness.app.R
import com.androidharness.app.data.ImageFrequency

@StringRes
internal fun imageFrequencyLabel(frequency: ImageFrequency): Int = when (frequency) {
    ImageFrequency.NONE -> R.string.chat_image_frequency_none
    ImageFrequency.LESS -> R.string.chat_image_frequency_less
    ImageFrequency.DEFAULT -> R.string.chat_image_frequency_default
    ImageFrequency.MORE -> R.string.chat_image_frequency_more
    ImageFrequency.ALWAYS -> R.string.chat_image_frequency_always
}

@StringRes
internal fun imageFrequencyDescription(frequency: ImageFrequency): Int = when (frequency) {
    ImageFrequency.NONE -> R.string.chat_image_frequency_none_desc
    ImageFrequency.LESS -> R.string.chat_image_frequency_less_desc
    ImageFrequency.DEFAULT -> R.string.chat_image_frequency_default_desc
    ImageFrequency.MORE -> R.string.chat_image_frequency_more_desc
    ImageFrequency.ALWAYS -> R.string.chat_image_frequency_always_desc
}
