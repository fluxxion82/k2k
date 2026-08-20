//
//  MainViewModel.swift
//  ios
//
//  Created by Sterling Albury on 5/5/23.
//

import Foundation
import presenter
import Combine

@MainActor
class MainViewModel: ObservableObject {
    @Published var peers = [String]()
    var mainPresenter: MainPresenter = MainPresenter(
        name: "mobile_ios", receiving: false
    )

    func startObservingPeers() {
        // Peer observation from Swift was never wired up: the KMP-NativeCoroutines path that
        // once lived here was commented out because the iOS discovery flow never populates
        // (KTOR-6489). When that is fixed, expose foundPeers via Swift export
        // (Flow -> AsyncSequence) rather than reintroducing a coroutines-interop pod.
        mainPresenter.onStart()
    }

    func stopObservingPeers() {
        mainPresenter.onStop()
    }
}
