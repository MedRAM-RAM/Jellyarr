package com.jellyarr.app.services

import com.jellyarr.app.model.ManagedService

/**
 * Contract governing service discovery, registration and status querying.
 */
interface ServiceRegistryContract {
    fun getService(id: String): ManagedService?
    fun getAllServices(): List<ManagedService>
    fun getRunningServicesCount(): Int
}
