//
//  MainViewModel.swift
//  ios
//
//  Created by Sterling Albury on 5/5/23.
//

import Foundation
import presenter
import Combine
import KMPNativeCoroutinesAsync
import KMPNativeCoroutinesRxSwift
import KMPNativeCoroutinesCombine
import KMPNativeCoroutinesCore

@MainActor
class MainViewModel: ObservableObject {
    @Published var peers = [String]()
    var mainPresenter: MainPresenter = MainPresenter(
        name: "mobile_ios", receiving: false
    )

    private var task: AnyCancellable? = nil
    
    func startObservingPeers() {
//        let publisher = createPublisher(for: mainPresenter.foundPeersFlow)
//
//        task = publisher.sink { completion in
//            print("Received completion: \(completion)")
//        } receiveValue: { value in
//            print("Received value: \(value)")
//            self.peers = [String]()
//            value.forEach { K2kHost in
//                self.peers.append(K2kHost.hostAddress)
//            }
//        }
        
        mainPresenter.onStart()
        
//        let observable = createObservable(for: mainPresenter.peerCommonFlow)
//
//        // Now use this observable as you would any other
//        let disposable = observable.subscribe(onNext: { value in
//            print("Received value: \(value)")
//        }, onError: { error in
//            print("Received error: \(error)")
//        }, onCompleted: {
//            print("Observable completed")
//        }, onDisposed: {
//            print("Observable disposed")
//        })
    }
        
    func stopObservingPeers() {
        mainPresenter.onStop()
        task?.cancel()
    }
}

