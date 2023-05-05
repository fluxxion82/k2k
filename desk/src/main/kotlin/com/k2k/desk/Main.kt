package com.k2k.desk

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.k2k.presenter.MainPresenter

fun main() = application {
    println("main")

    val mainPresenter = MainPresenter("desk", true)
    val peers by mainPresenter.foundPeers.collectAsState()
    Window(onCloseRequest = ::exitApplication) {
        Column(
            modifier = Modifier.fillMaxSize().padding(10.dp),
        ) {
            Text(
                modifier = Modifier.padding(10.dp),
                text = "Desktop Peer Two Peer"
            )

            LazyColumn {
                items(peers) {
                    Text(
                        modifier = Modifier
                            .padding(10.dp)
                            .clickable {
                                mainPresenter.onConnect(it)
                                       },
                        text = "${it.name} : ${it.hostAddress}:${it.port}"
                    )
                }
            }

            Button(
                modifier = Modifier.padding(10.dp),
                onClick = {
                    mainPresenter.onStart()
                }
            ) {
                Text("Start")
            }

            Button(
                modifier = Modifier.padding(10.dp),
                onClick = {
                    mainPresenter.onStop()
                }
            ) {
                Text("stop")
            }
        }
    }
}