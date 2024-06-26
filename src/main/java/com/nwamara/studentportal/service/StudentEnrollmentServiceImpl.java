package com.nwamara.studentportal.service;

import com.nwamara.studentportal.config.StudentPortalProperties;
import com.nwamara.studentportal.dao.CourseRepository;
import com.nwamara.studentportal.dao.InvoiceRepository;
import com.nwamara.studentportal.dao.StudentEnrollmentRepository;
import com.nwamara.studentportal.dao.StudentRepository;
import com.nwamara.studentportal.dto.*;
import com.nwamara.studentportal.exception.CourseNotFoundException;
import com.nwamara.studentportal.exception.NotFoundException;
import com.nwamara.studentportal.exception.StringResponseException;
import com.nwamara.studentportal.exception.StudentNotFoundException;
import com.nwamara.studentportal.persistence.Course;
import com.nwamara.studentportal.persistence.Invoice;
import com.nwamara.studentportal.persistence.Student;
import com.nwamara.studentportal.persistence.StudentEnrollment;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.ui.Model;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class StudentEnrollmentServiceImpl implements StudentEnrollmentService{

    private final StudentEnrollmentRepository studentEnrollmentRepository;
    private final InvoiceRepository invoiceRepository;
    private final StudentService studentService;
    private final ModelMapper modelMapper;
    private final StudentRepository studentRepository;
    private final CourseRepository courseRepository;
    private final WebClient webClient;
    private final StudentPortalProperties studentPortalProperties;


    @Autowired
    public StudentEnrollmentServiceImpl(StudentEnrollmentRepository studentEnrollmentRepository, InvoiceRepository invoiceRepository, StudentService studentService, ModelMapper modelMapper, StudentRepository studentRepository, CourseRepository courseRepository, WebClient webClient, StudentPortalProperties studentPortalProperties) {
        this.studentEnrollmentRepository = studentEnrollmentRepository;
        this.invoiceRepository = invoiceRepository;
        this.studentService = studentService;
        this.modelMapper = modelMapper;
        this.studentRepository = studentRepository;
        this.courseRepository = courseRepository;
        this.webClient = webClient;
        this.studentPortalProperties = studentPortalProperties;
    }
    @Override
    public String enrollStudentInCourse(String id, String courseId, Model model, RedirectAttributes redirAttrs) {
        Student existingStudent = studentRepository.findById(id).orElseThrow(()->{return new StudentNotFoundException(id);});
        Course course = courseRepository.findById(courseId).orElseThrow(()->{return new CourseNotFoundException(courseId);});
        List<Course> enrolledCourses = studentEnrollmentRepository.findCoursesByStudentId(id);
        if(enrolledCourses.isEmpty()){
            StudentDto upgradedStudent = studentService.UpgradeStudentById(id);
            Student s =modelMapper.map(upgradedStudent, Student.class);
            GeneratedResponseMessage responseMessage = studentEnrollment(s, course);
            if(responseMessage.getSuccessMessage() != null){
                model.addAttribute("course", course);
                boolean enrollmentSuccess = true;
                model.addAttribute("enrollmentSuccess", enrollmentSuccess);
                model.addAttribute("responseMessage", responseMessage);
                //redirAttrs.addFlashAttribute("success", "The error XYZ occurred.");
                return "courseDetailPage";
            }
            model.addAttribute("course", course);
            boolean enrollmentFailed = true;
            model.addAttribute("enrollmentFailed", enrollmentFailed);
            model.addAttribute("responseMessage", responseMessage);
            //redirAttrs.addFlashAttribute("success", "The error XYZ occurred.");
            return "courseDetailPage";
        }
        GeneratedResponseMessage responseMessage = studentEnrollment(existingStudent, course);
        model.addAttribute("course", course);
        boolean enrollmentSuccess = true;
        model.addAttribute("enrollmentSuccess", enrollmentSuccess);
        model.addAttribute("responseMessage", responseMessage);
        return "courseDetailPage";

    }

    @Override
    public String fetchStudentEnrolledCourses(String id, Model model) {
        List<Course> courses = studentEnrollmentRepository.findCoursesByStudentId(id);

        if (courses.isEmpty()) {
            throw new NotFoundException("No Course Found");
        }

        model.addAttribute("courses", courses);
        return "enrollments";

    }

    @Override
    public String fetchCourseById(Model model, String courseId) {
        Course course = courseRepository.findById(courseId).orElseThrow(()->{return new CourseNotFoundException(courseId);});
        GeneratedResponseMessage responseMessage = new GeneratedResponseMessage();
        model.addAttribute("course", course);
        model.addAttribute("responseMessage", responseMessage);

        return  "enrollmentDetailPage";
    }


    public GeneratedResponseMessage studentEnrollment(Student student, Course course) {

        StudentEnrollment checkEnrollment = studentEnrollmentRepository.findByStudentIdAndCourseId(student.getId(), course.getId());
        GeneratedResponseMessage responseMessage = new GeneratedResponseMessage();
        if(checkEnrollment != null){
            responseMessage.setFailedMessage("You are already enrolled in this course");
            return responseMessage;
            //throw  new StringResponseException("Student already enrolled in this course");
        }


        StudentEnrollment enrollment = new StudentEnrollment();
        //Student xStudent = modelMapper.map(studentDto, Student.class);
        enrollment.setStudent(student);
        enrollment.setCourse(course);
        StudentEnrollment savedEnrollment = studentEnrollmentRepository.save(enrollment);

        CreateInvoiceResponseDto invoiceResponseDto = savedEnrollment.getId() != null ?
                generateInvoice(student.getStudentRegistrationNumber(), course, student) : null;

        if (invoiceResponseDto == null || invoiceResponseDto.getReference() == null) {
            responseMessage.setFailedMessage("Invoice generation failed: try again.");
            //throw new StringResponseException("Invoice generation failed: try again.");
        }
//        EnrollmentResult result = new EnrollmentResult();
//        result.setStudentId(student.getId());
//        result.setEnrollmentId(savedEnrollment.getId());
//        result.setCourseId(course.getId());
//        result.setInvoiceReference(invoiceResponseDto.getReference());

        responseMessage.setSuccessMessage("You have successfully enrolled in this course");
        responseMessage.setAddedData(invoiceResponseDto.getReference());

        return responseMessage;
    }

    public CreateInvoiceResponseDto generateInvoice(String studentRegNumber, Course course, Student student){
        String url = studentPortalProperties.getFinanceInvoicesBaseUrl();
        String formattedDueDate = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));


        CreateInvoiceRequestDto invoice = new CreateInvoiceRequestDto();
        invoice.setAmount(course.getCourseFee());
        invoice.setDueDate(LocalDate.parse(formattedDueDate));
        invoice.setType("TUITION_FEES");

        CreateStudentFinanceAccountRequestDto account = new CreateStudentFinanceAccountRequestDto();
        account.setStudentId(studentRegNumber);
        invoice.setAccount(account);


        System.out.println(invoice);
        CreateInvoiceResponseDto data =  webClient.post()
                .uri(url)
                .bodyValue(invoice)
                .retrieve()
                .bodyToMono(CreateInvoiceResponseDto.class)
                .timeout(Duration.ofMillis(60000))
                .block();
        System.out.println(data);

        if(data.getReference() != null){
            saveInvoiceCopy(data, student, course);
        }
        return data;
    }

    public Invoice saveInvoiceCopy (CreateInvoiceResponseDto data, Student student, Course course){
        Invoice invoice = new Invoice();

        invoice.setStudent(student);
        invoice.setInvoiceReference(data.getReference());
        invoice.setAmount(data.getAmount());
        invoice.setPaymentItemId(course.getId());

        return invoiceRepository.save(invoice);

    }

}
