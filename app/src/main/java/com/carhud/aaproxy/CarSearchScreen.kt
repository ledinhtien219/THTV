package com.carhud.aaproxy

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template

class CarSearchScreen(carContext: CarContext, private val initialQuery: String = "") : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val callback = object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {
                CarMediaManager.updateSearchText(searchText)
            }

            override fun onSearchSubmitted(searchTerm: String) {
                if (searchTerm.isNotBlank()) {
                    CarMediaManager.submitSearchQuery(searchTerm)
                    screenManager.pop()
                }
            }
        }

        return SearchTemplate.Builder(callback)
            .setHeaderAction(Action.BACK)
            .setInitialSearchText(initialQuery)
            .setShowKeyboardByDefault(true)
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("📱 Phone")
                            .setOnClickListener {
                                CarMediaManager.launchPhoneSearchActivity(carContext, initialQuery)
                            }
                            .build()
                    )
                    .build()
            )
            .build()
    }
}
