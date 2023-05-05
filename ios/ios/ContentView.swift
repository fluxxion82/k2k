//
//  ContentView.swift
//  ios
//
//  Created by Sterling Albury on 5/4/23.
//

import SwiftUI
import CoreData
import presenter
//import KMPNativeCoroutinesAsync
//import KMPNativeCoroutinesRxSwift
//import KMPNativeCoroutinesCombine
//import KMPNativeCoroutinesCore

struct ContentView: View {
    @Environment(\.managedObjectContext) private var viewContext

    var mainPresenter: MainPresenter
    // var mainViewModel: MainViewModel
    
    var body: some View {
        NavigationView {
            VStack(alignment: .leading, spacing: 50) {
                Text("ios peer rwo peer")
                
                List {
                    ForEach(mainPresenter.foundPeers, id: \.self) { item in
                        Text(item.hostAddress)
                    }
                }

                Button(action: start) {
                    Label("Start", systemImage: "plus")
                }
                
                Button(action: stop) {
                    Label("Stop", systemImage: "minus")
                }
                
                Spacer()
            }
        }
    }
    
    // @MainActor
    private func start() {
        mainPresenter.onStart()
        // mainViewModel.startObservingPeers()
    }
    
    // @MainActor
    private func stop() {
        mainPresenter.onStop()
        /// mainViewModel.stopObservingPeers()
    }
}

// extension K2kHost: Identifiable { }

//struct ContentView_Previews: PreviewProvider {
//    static var previews: some View {
//        ContentView().environment(\.managedObjectContext, PersistenceController.preview.container.viewContext)
//    }
//}
