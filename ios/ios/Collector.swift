////
////  Collector.swift
////  ios
////
////  Created by Sterling Albury on 5/4/23.
////
//
//import Foundation
//import presenter
//
//class Collector<T> : Kotlinx_coroutines_coreFlowCollector {
//    let callback:(T) -> Void
//
//    init(callback: @escaping (T) -> Void) {
//        self.callback = callback
//    }
//    
//    func emit(value: Any?) async throws {
//        callback(value as! T)
//    }
//}
