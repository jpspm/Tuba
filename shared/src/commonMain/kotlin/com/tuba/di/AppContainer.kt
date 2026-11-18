package com.tuba.di

import com.tuba.TubaConfig
import com.tuba.data.AuthRepository
import com.tuba.data.BeachRepository
import com.tuba.data.MarineRepository
import com.tuba.data.OpenMeteoRepository
import com.tuba.data.ReportRepository
import com.tuba.data.fake.FakeAuthRepository
import com.tuba.data.fake.FakeBeachRepository
import com.tuba.data.fake.FakeReportRepository
import com.tuba.data.supabase.SupabaseAuthRepository
import com.tuba.data.supabase.SupabaseBeachRepository
import com.tuba.data.supabase.SupabaseReportRepository
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/** Sem credenciais do Supabase no local.properties, o app usa repositórios em memória. */
class AppContainer {
    val demoMode: Boolean = TubaConfig.SUPABASE_URL.isBlank() || TubaConfig.SUPABASE_ANON_KEY.isBlank()

    private val appScope = CoroutineScope(SupervisorJob())

    private val supabase by lazy {
        createSupabaseClient(TubaConfig.SUPABASE_URL, TubaConfig.SUPABASE_ANON_KEY) {
            install(Auth)
            install(Postgrest)
        }
    }

    val auth: AuthRepository =
        if (demoMode) FakeAuthRepository() else SupabaseAuthRepository(supabase, appScope)

    val beaches: BeachRepository =
        if (demoMode) FakeBeachRepository() else SupabaseBeachRepository(supabase)

    val reports: ReportRepository =
        if (demoMode) FakeReportRepository(auth) else SupabaseReportRepository(supabase)

    val marine: MarineRepository = OpenMeteoRepository()

    companion object {
        val instance: AppContainer by lazy { AppContainer() }
    }
}
