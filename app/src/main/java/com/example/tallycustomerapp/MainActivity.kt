package com.example.tallycustomerapp

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.*
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.example.tallycustomerapp.data.AppDatabase
import com.example.tallycustomerapp.databinding.ActivityMainBinding
import com.example.tallycustomerapp.web.WebAppInterface
import com.example.tallycustomerapp.web.SyncCollector
import android.content.Context
import android.view.*
import androidx.lifecycle.ViewModelProvider
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.tallycustomerapp.data.CompanyEntity
import com.example.tallycustomerapp.databinding.FragmentOfflineBinding
import com.example.tallycustomerapp.databinding.ItemCompanyBinding
import com.example.tallycustomerapp.offline.OfflineViewModel
import com.example.tallycustomerapp.offline.OfflineViewModelFactory
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val db by lazy { AppDatabase.getDatabase(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupNavigation()
    }

    private fun setupNavigation() {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, LivePortalFragment())
            .commit()
        binding.bottomNav.setOnItemSelectedListener { menuItem ->
            when (menuItem.itemId) {
                R.id.tab_live -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, LivePortalFragment())
                        .commit()
                    true
                }
                R.id.tab_offline -> {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragment_container, OfflineFragment())
                        .commit()
                    true
                }
                else -> false
            }
        }
    }

    class LivePortalFragment : Fragment() {
        private lateinit var db: AppDatabase

        @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
        override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
        ): View {
            val view = inflater.inflate(R.layout.fragment_live, container, false)
            val webView: WebView = view.findViewById(R.id.webView)
            db = AppDatabase.getDatabase(requireContext())

            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.setSupportMultipleWindows(false)
            webView.settings.userAgentString = webView.settings.userAgentString + " AndroidNativeWebView"

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)
            restoreCookies(requireContext(), cookieManager)

            webView.addJavascriptInterface(
                WebAppInterface(requireContext(), db),
                "AndroidBridge"
            )

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    saveCookies(requireContext(), cookieManager)
                    injectCompanySyncJs(webView)
                }
            }

            webView.loadUrl("https://customer.tallysolutions.com/customerapp/")
            return view
        }

        private fun injectCompanySyncJs(webView: WebView) {
            webView.evaluateJavascript(SyncCollector.script(), null)
        }

        private fun saveCookies(context: Context, cookieManager: CookieManager) {
            val cookies = cookieManager.getCookie("https://customer.tallysolutions.com/customerapp/")
            context.getSharedPreferences("cookies", Context.MODE_PRIVATE).edit()
                .putString("tally_cookies", cookies)
                .apply()
        }

        private fun restoreCookies(context: Context, cookieManager: CookieManager) {
            val cookies = context.getSharedPreferences("cookies", Context.MODE_PRIVATE)
                .getString("tally_cookies", null)
            if (!cookies.isNullOrEmpty()) {
                cookieManager.setCookie("https://customer.tallysolutions.com/customerapp/", cookies)
                CookieManager.getInstance().flush()
            }
        }
    }

    class OfflineFragment : Fragment() {
        private var _binding: FragmentOfflineBinding? = null
        private val binding get() = _binding!!
        private lateinit var adapter: CompanyAdapter
        private lateinit var viewModel: OfflineViewModel

        override fun onCreateView(
            inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
        ): View {
            _binding = FragmentOfflineBinding.inflate(inflater, container, false)
            val db = AppDatabase.getDatabase(requireContext())
            viewModel = ViewModelProvider(this, OfflineViewModelFactory(db.companyDao()))
                .get(OfflineViewModel::class.java)
            setupRecyclerView()
            observeViewModel()
            return binding.root
        }

        override fun onResume() {
            super.onResume()
            viewModel.loadCompanies()
        }

        private fun setupRecyclerView() {
            adapter = CompanyAdapter { company ->
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(company.companyName)
                    .setMessage(formatCompany(company))
                    .setPositiveButton("Close", null)
                    .show()
            }
            binding.recyclerOffline.layoutManager = LinearLayoutManager(requireContext())
            binding.recyclerOffline.adapter = adapter
        }

        private fun observeViewModel() {
            viewModel.companiesLiveData.observe(viewLifecycleOwner) { companies ->
                adapter.submitList(companies)
                binding.textEmpty.visibility = if (companies.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        private fun formatCompany(company: CompanyEntity): String = buildString {
            append("Company: ${company.companyName}\n")
            append("Serial No: ${company.serialNumber}\n")
            company.gstin?.let { append("GSTIN: $it\n") }
            company.financialYearFrom?.let { append("FY From: $it\n") }
            append("Last Sync: ${android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", company.lastSynced)}")
        }

        override fun onDestroyView() {
            super.onDestroyView()
            _binding = null
        }
    }

    class CompanyAdapter(
        val onItemClick: (CompanyEntity) -> Unit
    ) : androidx.recyclerview.widget.ListAdapter<CompanyEntity, CompanyAdapter.VH>(
        object : androidx.recyclerview.widget.DiffUtil.ItemCallback<CompanyEntity>() {
            override fun areItemsTheSame(old: CompanyEntity, new: CompanyEntity) = old.id == new.id
            override fun areContentsTheSame(old: CompanyEntity, new: CompanyEntity) = old == new
        }
    ) {
        inner class VH(val binding: ItemCompanyBinding) : androidx.recyclerview.widget.RecyclerView.ViewHolder(binding.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            return VH(ItemCompanyBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
        override fun onBindViewHolder(holder: VH, position: Int) {
            val company = getItem(position)
            holder.binding.textCompanyName.text = company.companyName
            holder.binding.textSerialNumber.text = "Serial No: ${company.serialNumber}"
            holder.binding.textLastSynced.text = "Synced: ${android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", company.lastSynced)}"
            holder.binding.root.setOnClickListener { onItemClick(company) }
        }
    }
}