package com.desklink.android.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    // Repository bindings will be added as implementations are created
    // e.g. @Binds abstract fun bindConnectionRepository(...): ConnectionRepository
}
