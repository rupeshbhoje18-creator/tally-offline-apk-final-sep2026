package com.example.tallycustomerapp.data

import androidx.room.Dao
import androidx.room.Query

@Dao
interface CompanyDao {
    @Query("SELECT * FROM companies ORDER BY lastSynced DESC")
    suspend fun getAllCompanies(): List<CompanyEntity>
}
