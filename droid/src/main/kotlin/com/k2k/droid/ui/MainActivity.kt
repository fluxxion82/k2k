package com.k2k.droid.ui

import android.net.wifi.WifiManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.k2k.presenter.MainPresenter
import com.k2k.droid.ui.theme.K2KTheme

class MainActivity : ComponentActivity() {
    private val mainPresenter = MainPresenter("mobile_droid", false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            K2KTheme {
                val peers by mainPresenter.foundPeers.collectAsState()

                // A surface container using the 'background' color from the theme
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {

                    Column(
                        modifier = Modifier.fillMaxSize().padding(50.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            modifier = Modifier.padding(10.dp),
                            text = "Android Peer Two Peer"
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
                                val wifi = getSystemService(WIFI_SERVICE) as WifiManager
                                val lock = wifi.createMulticastLock("mylock")
                                lock.acquire()
                                mainPresenter.onStart()
                            }
                        ) {
                            Text("Start")
                        }

                        Button(
                            modifier = Modifier.padding(10.dp),
                            onClick = mainPresenter::onStop
                        ) {
                            Text("stop")
                        }
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        mainPresenter.onCleared()
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    K2KTheme {
        Greeting("Android")
    }
}