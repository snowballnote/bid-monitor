import React from 'react';
import { createRoot } from 'react-dom/client';
import { HashRouter, Navigate, Route, Routes } from 'react-router-dom';
import AppLayout from './components/AppLayout';
import Dashboard from './pages/Dashboard';
import BidNotices from './pages/bids/BidNotices';
import SubmissionProjects from './pages/submissions/SubmissionProjects';
import SubmissionDetail from './pages/submissions/SubmissionDetail';
import Documents from './pages/documents/Documents';
import PerformanceProjects from './pages/performances/PerformanceProjects';
import PerformanceDetail from './pages/performances/PerformanceDetail';
import '../../src/main/resources/static/common.css';
import '../../src/main/resources/static/home.css';

createRoot(document.getElementById('root')).render(
  <HashRouter>
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<Dashboard />} />
        <Route path="bids" element={<BidNotices />} />
        <Route path="submissions" element={<SubmissionProjects />} />
        <Route path="submissions/:caseId" element={<SubmissionDetail />} />
        <Route path="documents" element={<Documents />} />
        <Route path="performances" element={<PerformanceProjects />} />
        <Route path="performances/:projectId" element={<PerformanceDetail />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  </HashRouter>,
);
