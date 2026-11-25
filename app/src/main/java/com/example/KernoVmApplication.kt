package com.example

import android.app.Application
import com.example.data.ImageRepository
import com.example.data.KernoDatabase
import com.example.data.VmRepository
import com.example.vm.VmSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class KernoVmApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var database: KernoDatabase
        private set

    lateinit var vmRepository: VmRepository
        private set

    lateinit var imageRepository: ImageRepository
        private set

    lateinit var vmSessionManager: VmSessionManager
        private set

    override fun onCreate() {
        super.onCreate()
        database = KernoDatabase.getInstance(this)
        vmRepository = VmRepository(database.vmProfileDao())
        imageRepository = ImageRepository(this, database.localImageDao())
        vmSessionManager = VmSessionManager(this, vmRepository, imageRepository)

        appScope.launch {
            database.seedDefaultsIfNeeded()
        }
    }
}
