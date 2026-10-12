package github.axolotl.aclass

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import github.axolotl.aclass.data.FileTimetableStorage
import github.axolotl.aclass.data.SchoolClient
import github.axolotl.aclass.data.TimetableRepository

class TimetableApplication : Application() {
    val repository by lazy { TimetableRepository(FileTimetableStorage(filesDir, noBackupFilesDir), SchoolClient()) }

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { repository.refreshAutomatically() }
        })
    }
}
