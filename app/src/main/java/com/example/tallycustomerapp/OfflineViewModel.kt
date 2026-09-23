package com.example.tallycustomerapp.offline

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.tallycustomerapp.data.CompanyDao
import com.example.tallycustomerapp.data.CompanyEntity
import kotlinx.coroutines.launch

class OfflineViewModel(private val companyDao: CompanyDao) : ViewModel() {
    val companiesLiveData = MutableLiveData<List<CompanyEntity>>()

    fun loadCompanies() {
        viewModelScope.launch {
            runCatching { companyDao.getAllCompanies() }
                .onSuccess { companiesLiveData.postValue(it) }
                .onFailure { companiesLiveData.postValue(emptyList()) }
        }
    }
}
