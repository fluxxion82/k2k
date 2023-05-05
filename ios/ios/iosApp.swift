//
//  iosApp.swift
//  ios
//
//  Created by Sterling Albury on 5/4/23.
//

import SwiftUI
import presenter

@main
struct iosApp: App {
    let persistenceController = PersistenceController.shared

    var body: some Scene {
        WindowGroup {
            ContentView(
                //mainViewModel: MainViewModel()
                mainPresenter: MainPresenter(name: "mobile_ios", receiving: false)
            )
                .environment(\.managedObjectContext, persistenceController.container.viewContext)
        }
    }
}
