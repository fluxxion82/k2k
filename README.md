# k2k
Rewrite of https://github.com/DATL4G/Klient2Klient using ktor. Experimenting with udp socket connections and packet transfer.

`desk` module: Example desktop app
`droid` module: Example android app
`ios` module: Example ios app
`k2k` module: Main peer to peer library
`presenter` module: Share presenter/vm logic for example apps.

Start Desktop app and Android app. Click 'start' on in the app to start discovery. Once you see the peer you want to connect to listed in both apps, click on the peer in the list to start a connection and transfer of data.

ios runs and is discoverable on desktop and android, but the `hosts` flow in DiscoveryServer doesn't seem to get updated and therefore can't populate the UI with found peers. 

Seeing 'Address already in use' exceptions pretty often when stopping and restarting. ios also seems to crash if the desktop app starts discovery first.

To build for ios, run the `build_pods.sh` script or the commands therein.
