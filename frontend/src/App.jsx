import { BrowserRouter, Routes, Route } from 'react-router-dom'
import Portal from './pages/Portal.jsx'
import Admin from './pages/Admin.jsx'
import Tech from './pages/Tech.jsx'
import PayPortal from './pages/PayPortal.jsx'

export default function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<Portal />} />
        <Route path="/admin" element={<Admin />} />
        <Route path="/tech" element={<Tech />} />
        <Route path="/pay" element={<PayPortal />} />
      </Routes>
    </BrowserRouter>
  )
}
