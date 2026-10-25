package com.zapline.iptv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zapline.iptv.data.AndroidPlatform

/** Keeps the shared [MainViewModel] alive across rotations, tied to Android's view-model lifecycle. */
class MainViewModelHolder(app: Application) : AndroidViewModel(app) {
    val vm = MainViewModel(AndroidPlatform(app), viewModelScope)
}
