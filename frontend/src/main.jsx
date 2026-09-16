import React from 'react';
import { createRoot } from 'react-dom/client';
import { HashRouter, Navigate, Route, Routes } from 'react-router-dom';
import AppLayout from './components/AppLayout';
import Dashboard from './pages/Dashboard';
import SubmissionProjects from './pages/submissions/SubmissionProjects';
import SubmissionDetail from './pages/submissions/SubmissionDetail';
import Documents from './pages/documents/Documents';
import '../../src/main/resources/static/common.css';
import '../../src/main/resources/static/home.css';

createRoot(document.getElementById('root')).render(
  <HashRouter>
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<Dashboard />} />
        <Route path="submissions" element={<SubmissionProjects />} />
        <Route path="submissions/:caseId" element={<SubmissionDetail />} />
        <Route path="documents" element={<Documents />} />
        <Route path="*" element={<Navigate to="/" replace />} />
      </Route>
    </Routes>
  </HashRouter>,
);
