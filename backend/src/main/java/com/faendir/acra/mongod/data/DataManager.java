package com.faendir.acra.mongod.data;

import com.faendir.acra.mongod.model.App;
import com.faendir.acra.mongod.model.Bug;
import com.faendir.acra.mongod.model.ProguardMapping;
import com.faendir.acra.mongod.model.Report;
import com.faendir.acra.mongod.model.ReportInfo;
import com.mongodb.BasicDBObjectBuilder;
import com.mongodb.gridfs.GridFSDBFile;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.gridfs.GridFsTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.Base64Utils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * @author Lukas
 * @since 21.05.2017
 */
@Component
public class DataManager {
    private static final String APP_REPORT_CACHE = "appReport";
    private static final String APP_CACHE = "app";
    private static final String BUG_REPORT_CACHE = "bugReport";
    private static final String APP_BUG_CACHE = "appBug";
    private final MappingRepository mappingRepository;
    private final ReportRepository reportRepository;
    private final AppRepository appRepository;
    private final BugRepository bugRepository;
    private final List<ListenerHolder<?>> listeners;
    private final GridFsTemplate gridFsTemplate;
    private final Logger logger;
    private final SecureRandom secureRandom;
    private final ExecutorService listenerExecutor;

    @Autowired
    public DataManager(SecureRandom secureRandom, AppRepository appRepository, GridFsTemplate gridFsTemplate, MappingRepository mappingRepository, ReportRepository reportRepository, BugRepository bugRepository) {
        this.secureRandom = secureRandom;
        this.appRepository = appRepository;
        this.bugRepository = bugRepository;
        this.gridFsTemplate = gridFsTemplate;
        this.mappingRepository = mappingRepository;
        this.reportRepository = reportRepository;
        this.logger = LoggerFactory.getLogger(DataManager.class);
        this.listeners = new ArrayList<>();
        this.listenerExecutor = Executors.newSingleThreadExecutor();
    }

    @CacheEvict(value = APP_CACHE, allEntries = true)
    public synchronized void createNewApp(String name) {
        byte[] bytes = new byte[12];
        secureRandom.nextBytes(bytes);
        appRepository.save(new App(name, Base64Utils.encodeToString(bytes)));
    }

    @Cacheable(value = APP_CACHE)
    public List<App> getApps() {
        return appRepository.findAll();
    }

    public App getApp(String id) {
        return appRepository.findOne(id);
    }

    @CacheEvict(value = APP_CACHE, allEntries = true)
    public synchronized void deleteApp(String id) {
        appRepository.delete(id);
        getReportsForApp(id).forEach(this::deleteReport);
        mappingRepository.delete(getMappings(id));
    }

    public List<GridFSDBFile> getAttachments(String report) {
        return gridFsTemplate.find(new Query(Criteria.where("metadata.reportId").is(report)));
    }

    public synchronized void addMapping(String app, int version, String mappings) {
        mappingRepository.save(new ProguardMapping(app, version, mappings));
    }

    private ProguardMapping getMapping(String app, int version) {
        return mappingRepository.findOne(new ProguardMapping.MetaData(app, version));
    }

    public List<ProguardMapping> getMappings(String app) {
        return mappingRepository.findByApp(app);
    }

    public void newReport(String app, JSONObject content) {
        newReport(app, content, Collections.emptyList());
    }

    @SuppressWarnings("UnusedReturnValue")
    @Caching(evict = {
            @CacheEvict(value = APP_REPORT_CACHE, key = "#a0"),
            @CacheEvict(value = BUG_REPORT_CACHE, key = "#result.stacktrace.hashCode() + \" \" + #result.versionCode")})
    public synchronized Report newReport(String app, JSONObject content, List<MultipartFile> attachments) {
        Report report = reportRepository.save(new Report(content, app));
        for (MultipartFile a : attachments) {
            try {
                gridFsTemplate.store(a.getInputStream(), a.getOriginalFilename(), a.getContentType(), new BasicDBObjectBuilder().add("reportId", report.getId()).get());
            } catch (IOException e) {
                logger.warn("Failed to load attachment", e);
            }
        }
        Bug bug = bugRepository.findOne(new Bug.Identification(report.getStacktrace().hashCode(), report.getVersionCode()));
        if (bug == null) {
            addBug(new Bug(app, report.getStacktrace(), report.getVersionCode()));
        }
        notifyListeners(new ReportInfo(report));
        return report;
    }

    @Cacheable(APP_REPORT_CACHE)
    public List<ReportInfo> getReportsForApp(String app) {
        try (Stream<Report> stream = reportRepository.findByApp(app)) {
            return stream.map(ReportInfo::new).collect(Collectors.toList());
        }
    }

    public long reportCountForApp(String app) {
        return reportRepository.countByApp(app);
    }

    public Report getReport(String id) {
        return reportRepository.findOne(id);
    }

    @Caching(evict = {
            @CacheEvict(value = APP_REPORT_CACHE, key = "#a0.app"),
            @CacheEvict(value = BUG_REPORT_CACHE, key = "#a0.stacktrace.hashCode() + \" \" + #a0.versionCode")})
    public synchronized void deleteReport(ReportInfo report) {
        reportRepository.delete(report.getId());
        gridFsTemplate.delete(new Query(Criteria.where("metadata.reportId").is(report.getId())));
        if (reportRepository.countByBug(report.getStacktrace(), report.getVersionCode()) == 0) {
            Optional.ofNullable(bugRepository.findOne(new Bug.Identification(report.getStacktrace().hashCode(), report.getVersionCode()))).ifPresent(this::deleteBug);
        }
        notifyListeners(report);
    }

    @SuppressWarnings("WeakerAccess")
    @CacheEvict(value = APP_BUG_CACHE, key = "#a0.app")
    public void deleteBug(Bug bug) {
        bugRepository.delete(bug);
        notifyListeners(bug);
    }

    @SuppressWarnings("WeakerAccess")
    @CacheEvict(value = APP_BUG_CACHE, key = "#a0.app")
    public void addBug(Bug bug) {
        bugRepository.save(bug);
        notifyListeners(bug);
    }

    @Cacheable(value = APP_BUG_CACHE)
    public List<Bug> getBugs(String app) {
        return bugRepository.findByApp(app);
    }

    @Cacheable(value = BUG_REPORT_CACHE, key = "#a0.stacktrace.hashCode() + \" \" + #a0.versionCode")
    public List<ReportInfo> getReportsForBug(Bug bug) {
        try (Stream<Report> stream = reportRepository.findByBug(bug.getStacktrace(), bug.getVersionCode())) {
            return stream.map(ReportInfo::new).collect(Collectors.toList());
        }
    }

    public long reportCountForBug(Bug bug) {
        return reportRepository.countByBug(bug.getStacktrace(), bug.getVersionCode());
    }

    public String retrace(Report report) {
        ProguardMapping mapping = getMapping(report.getApp(), report.getVersionCode());
        if (mapping != null) {
            try {
                return ReportUtils.retrace(report.getStacktrace(), mapping);
            } catch (IOException ignored) {
            }
        }
        return report.getStacktrace();
    }

    public void setBugSolved(Bug bug, boolean solved) {
        bug.setSolved(solved);
        bugRepository.save(bug);
    }

    public <T> void addListener(Listener<T> listener, Class<T> clazz) {
        listeners.add(new ListenerHolder<>(listener, clazz));
    }

    public void removeListener(Listener<?> listener) {
        listeners.removeIf(h -> h.listener.equals(listener));
    }

    private void notifyListeners(Object change) {
        listeners.forEach(h -> listenerExecutor.submit(() -> h.callIfAssignable(change)));
    }

    public interface Listener<T> {
        void onChange(T t);
    }

    private static class ListenerHolder<T> {
        private final Listener<T> listener;
        private final Class<T> clazz;

        private ListenerHolder(Listener<T> listener, Class<T> clazz) {
            this.listener = listener;
            this.clazz = clazz;
        }

        private void callIfAssignable(Object change) {
            if (clazz.isInstance(change)) {
                listener.onChange(clazz.cast(change));
            }
        }
    }

}