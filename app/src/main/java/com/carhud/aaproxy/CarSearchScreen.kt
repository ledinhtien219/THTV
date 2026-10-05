package com.carhud.aaproxy

import android.widget.Toast
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class CarSearchScreen(carContext: CarContext, private val input: CarInputSession) : Screen(carContext) {
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = input.cancel()
        })
    }

    private fun submit(text: String) {
        input.submit(text) { accepted ->
            if (accepted) screenManager.pop()
            else Toast.makeText(carContext, "Chưa nhập được. Kiểm tra lại ô nhập hoặc địa chỉ.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onGetTemplate(): Template {
        return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) = input.update(searchText)
            override fun onSearchSubmitted(searchTerm: String) = submit(searchTerm)
        })
            .setHeaderAction(Action.BACK)
            .setSearchHint(input.hint)
            .setInitialSearchText(input.text)
            .setShowKeyboardByDefault(true)
            // This also allows clearing a generic web field to an empty string.
            .setActionStrip(ActionStrip.Builder().addAction(
                Action.Builder().setTitle(if (input.allowEmpty) "Nhập" else "Tìm")
                    .setOnClickListener { submit(input.text) }.build()
            ).build())
            .build()
    }
}
